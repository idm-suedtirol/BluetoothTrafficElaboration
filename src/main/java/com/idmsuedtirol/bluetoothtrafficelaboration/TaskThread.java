/*

BluetoothTrafficElaboration: various elaborations of traffic data

Copyright (C) 2017 IDM Südtirol - Alto Adige - Italy

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

*/

package com.idmsuedtirol.bluetoothtrafficelaboration;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;

import com.idmsuedtirol.bluetoothtrafficelaboration.DatabaseHelper.ConnectionReady;
import com.idmsuedtirol.bluetoothtrafficelaboration.ElaborationsInfo.TaskInfo;

/**
 * @author Davide Montesin <d@vide.bz>
 */
public class TaskThread extends Thread
{
   DatabaseHelper databaseHelper;

   boolean        stop          = false;
   boolean        sleeping      = false;
   long           sleepingUntil;

   final Object   exclusiveLock = new Object();

   public TaskThread(DatabaseHelper databaseHelper)
   {
      this.databaseHelper = databaseHelper;
   }

   @Override
   public void run()
   {
      while (true)
      {
         synchronized (this.exclusiveLock)
         {
            this.sleeping = false;
            Thread.interrupted(); // clear interrupt flag if set
            if (this.stop)
               return;
         }
         try
         {
            this.executeElaborations();
         }
         catch (Exception exxx)
         {
            // TODO send error to crashbox and retry
            exxx.printStackTrace();
         }
         long sleepTime;
         synchronized (this.exclusiveLock)
         {
            if (this.stop)
               return;
            this.sleeping = true;
            // Sleep until next :00 minute
            long now = System.currentTimeMillis();
            long hour = 3600L * 1000L;
            long usedPartialHour = now % hour;
            sleepTime = hour - usedPartialHour;
            this.sleepingUntil = now + sleepTime;
         }
         try
         {
            Thread.sleep(sleepTime);
         }
         catch (InterruptedException e)
         {
            synchronized (this.exclusiveLock)
            {
               if (!this.stop)
               {
                  throw new IllegalStateException("Who interrupt me?", e);
               }
            }
         }
      }
   }

   private void executeElaborations() throws SQLException, IOException
   {
      // TODO log start of elaborations
      ArrayList<TaskInfo> tasks = this.databaseHelper.newSelectTaskInfo();
      boolean someTaskFail = false;
      for (int i = 0; i < tasks.size(); i++)
      {
         long startTime = System.currentTimeMillis();
         TaskInfo task = tasks.get(i);
         this.databaseHelper.newUpdate("update scheduler_task set status = ?, last_run_time = ? where id = ?",
                                       new Object[] { "RUNNING", new Timestamp(startTime), task.id });
         // RUNNING
         String status;
         String run_output = "";
         try
         {
            if (someTaskFail)
            {
               status = "PREV-FAIL";
            }
            else
            {
               if (task.function_name.equals("count_bluetooth_intime"))
               {
                  run_output = ElaborationCountBluetooth.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("create_bluetooth_lhv"))
               {
                  run_output = ElaborationCreateBluetoothLhv.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("create_matches"))
               {
                  run_output = ElaborationMatchBluetooth.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("count_match_intime"))
               {
                  run_output = ElaborationCountMatchBluetooth.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("run_mode_intime"))
               {
                  run_output = ElaborationModeMatchBluetooth.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("run_mode_intime_100kmh"))
               {
                  run_output = ElaborationModeMatchBluetooth100kmh.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("compute_bspeed"))
               {
                  run_output = ElaborationSpeedMatchBluetooth.doElaboration(this.databaseHelper, task.args);
               }
               else if (task.function_name.equals("compute_bspeed_100kmh"))
               {
                  run_output = ElaborationSpeedMatchBluetooth100kmh.doElaboration(this.databaseHelper, task.args);
               }
               else
               {
                  // TODO remove fake elaboration
                  Thread.sleep(5000);
               }
               status = "DONE";
               // long start = System.currentTimeMillis();
               this.databaseHelper.newCommand("analyze measurementhistory");
               // System.out.println("analyze: " + (System.currentTimeMillis() - start));
            }
         }
         catch (Exception exxx)
         {
            someTaskFail = true;
            status = "FAIL";
            StringWriter sw = new StringWriter();
            exxx.printStackTrace(new PrintWriter(sw));
            run_output = sw.toString();
         }
         this.databaseHelper.newUpdate("update scheduler_task set status = ? where id = ?",
                                       new Object[] { status, task.id });
         long finishTime = System.currentTimeMillis();
         this.databaseHelper.newUpdate("insert into scheduler_run (task_id, status, start_time, stop_time, run_output) " +
                                       " values (?,?,?,?,?)",
                                       new Object[] { task.id, status, new Timestamp(startTime),
                                             new Timestamp(finishTime), run_output });
         synchronized (this.exclusiveLock)
         {
            if (this.stop)
               return;
         }
      }
      // Update last elaboration cache
      this.databaseHelper.newConnection(new ConnectionReady<int[]>()
      {
         @Override
         public int[] connected(Connection conn) throws SQLException, IOException
         {
            String query = DatabaseHelper.readResource(this.getClass(), "last_elaboration_cache.sql");
            ResultSet resultSet = conn.createStatement().executeQuery(query);
            resultSet.next();
            int nr_insert = resultSet.getInt("nr_insert");
            int nr_update = resultSet.getInt("nr_update");
            conn.commit();
            return new int[]{nr_insert, nr_update};
         }
      });
   }

   @Override
   public void interrupt()
   {
      synchronized (this.exclusiveLock)
      {
         this.stop = true;
         if (this.sleeping)
         {
            super.interrupt();
         }
      }

   }
   
   /*
    * Method used only for development/debugging
    */
   public static void main(String[] args) throws FileNotFoundException, IOException, ClassNotFoundException 
   {
	   DatabaseHelper databaseHelper = BluetoothTrafficElaborationServlet.createDatabaseHelper();
	   TaskThread taskThread = new TaskThread(databaseHelper);
	   taskThread.run();
   }

}

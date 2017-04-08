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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;

import com.idmsuedtirol.bluetoothtrafficelaboration.ElaborationsInfo.TaskInfo;

/**
 * @author Davide Montesin <d@vide.bz>
 */
public class TaskThread extends Thread
{
   DatabaseHelper databaseHelper;

   boolean        stop          = false;
   boolean        sleeping      = false;

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
         synchronized (this.exclusiveLock)
         {
            if (this.stop)
               return;
            this.sleeping = true;
         }
         try
         {
            Thread.sleep(15L * 60L * 1000L);
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
               else
               {
                  // TODO remove fake elaboration
                  Thread.sleep(5000);
               }
               // TODO remove fake exception
               if (i == 5)
                  throw new IllegalStateException();
               status = "DONE";
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

}

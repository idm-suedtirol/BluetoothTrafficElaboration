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
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import org.apache.commons.lang3.StringEscapeUtils;

import com.idmsuedtirol.bluetoothtrafficelaboration.DatabaseHelper.ConnectionReady;

/**
 * @author Davide Montesin <d@vide.bz>
 */
public class ElaborationCountBluetooth
{
   static String doElaboration(DatabaseHelper databaseHelper, final String args) throws SQLException, IOException
   {

      final ArrayList<Station> activeStations = databaseHelper.newSelectBluetoothStations();

      String result = databaseHelper.newConnection(new ConnectionReady<String>()
      {
         @Override
         public String connected(Connection conn) throws SQLException, IOException
         {
            String sql = DatabaseHelper.readResource(this.getClass(), "elaboration_count_bluetooth.sql");
            PreparedStatement ps = conn.prepareStatement(sql);
            StringBuilder result = new StringBuilder("[");
            for (int s = 0; s < activeStations.size(); s++)
            {
               Station station = activeStations.get(s);
               result.append(String.format("%s{ id:%3d, name:%-14s, log:",
                                           s == 0 ? "" : " ",
                                           station.id,
                                           "'" + StringEscapeUtils.escapeEcmaScript(station.stationcode) + "'"));
               ps.setInt(1, Integer.parseInt(args));
               ps.setInt(2, station.id);
               ResultSet rs = ps.executeQuery();
               if (!rs.next())
                  throw new SQLException("one row expected");
               Array array = rs.getArray(1);
               Integer[] counters = (Integer[]) array.getArray();
               result.append(String.format("{ins:%4d, upd:%4d, del:%4d}}", counters[2], counters[1], counters[0]));
               result.append(s < activeStations.size() - 1 ? ",\n" : "");
               conn.commit();
            }
            return result.toString() + "]";
         }
      });
      return result;
   }
}

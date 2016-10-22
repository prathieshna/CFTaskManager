package com.iit.prathieshna.taskmanager;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.github.lzyzsd.circleprogress.ArcProgress;
import com.github.lzyzsd.circleprogress.CircleProgress;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Dashboard extends Activity {
    private ArcProgress batteryLevel;
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            batteryLevel.setProgress(level);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    final float cpuUsage = readUsage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ArcProgress cpuUsageMeter = (ArcProgress) findViewById(R.id.arc_progress_CPU);
                            cpuUsageMeter.setProgress((int) cpuUsage);
                        }
                    });
                }
            }
        }).start();
        batteryLevel = (ArcProgress) findViewById(R.id.arc_progress_BATTERY);
        this.registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.unregisterReceiver(this.mBatInfoReceiver);
    }

    public void TestConnection(View v) {
        EditText ip = (EditText) findViewById(R.id.ipAddress);
        EditText port = (EditText) findViewById(R.id.portNumber);
        TextView wifi_strength = (TextView) findViewById(R.id.wifi_strength);
        TextView surrogate_power = (TextView) findViewById(R.id.surrogate_power);
        TextView surrogate_load = (TextView) findViewById(R.id.surrogate_load);
        TextView available_ram = (TextView) findViewById(R.id.available_ram);
        CircleProgress surrogateIndex = (CircleProgress) findViewById(R.id.circle_progress);

        wifi_strength.setText("");
        surrogate_power.setText("");
        surrogate_load.setText("");
        available_ram.setText("");
        surrogateIndex.setProgress(0);

        if (ip.getText().toString().trim().length() == 0 || port.getText().toString().trim().length() == 0) {
            Toast.makeText(getApplicationContext(), "Please enter both IP and Port", Toast.LENGTH_SHORT).show();
        } else {
            try {
                ExecutorService executorService = Executors.newFixedThreadPool(1);
                List<Callable<Object>> lst = new ArrayList<>();
                lst.add(new InitializeThread("context", ip.getText().toString(), Integer.parseInt(port.getText().toString())));
                System.out.println(ip.getText().toString() + " " + Integer.parseInt(port.getText().toString()));
                List<Future<Object>> tasks = executorService.invokeAll(lst);
                for (Future<Object> task : tasks) {
                    if (task.get() != null) {
                        System.out.println(task.get().toString());
                        String[] result = task.get().toString().split(",");
                        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        int numberOfLevels = 5;
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        int level = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), numberOfLevels);
                        wifi_strength.setText(level + "/" + numberOfLevels);
                        surrogate_power.setText("AC " + result[0] + " " + result[1] + "%");
                        surrogate_load.setText(result[2]);
                        available_ram.setText(result[3] + " MB");
                        surrogateIndex.setProgress(Integer.parseInt(result[4]));
                        if(v == findViewById(R.id.button))
                        {
                            ObjectOutputStream outputStream = new ObjectOutputStream(new FileOutputStream(Environment.getExternalStorageDirectory()+ File.separator + "surrogate.txt"));
                            outputStream.writeObject(ip.getText().toString() + "," + port.getText().toString());
                            outputStream.flush();
                            outputStream.close();
                            Toast.makeText(getApplicationContext(), "Device Pairing Successful!", Toast.LENGTH_SHORT).show();
                        }
                    } else{
                        System.err.println("Problem!");
                        Toast.makeText(getApplicationContext(), "Please check both IP and Port", Toast.LENGTH_SHORT).show();
                    }
                }
                executorService.shutdown();
            } catch (InterruptedException | ExecutionException e) {
                Toast.makeText(getApplicationContext(), "Connection Failed! Try Again.", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }  catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class InitializeThread implements Callable<Object> {
        private String data;
        private String ip;
        private int port;

        public InitializeThread(String _data, String _ip, int _port) {
            this.data = _data;
            this.ip = _ip;
            this.port = _port;
        }

        @Override
        public Object call() throws Exception {
            try {
                final Socket socket = new Socket(this.ip, this.port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.println(this.data);
                Thread closeSocketOnShutdown = new Thread() {
                    public void run() {
                        try {
                            socket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                };
                Runtime.getRuntime().addShutdownHook(closeSocketOnShutdown);
                return in.readLine();

            } catch (UnknownHostException e) {
                System.err.println("Socket connection problem (Unknown host)" + e);
            } catch (IOException e) {
                System.err.println("Could not initialize I/O on socket " + e);
            }
            return null;
        }
    }

    private float readUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();

            String[] toks = load.split(" +");  // Split on one or more spaces

            long idle1 = Long.parseLong(toks[4]);
            long cpu1 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5])
                    + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);

            try {
                Thread.sleep(360);
            } catch (Exception e) {
                e.printStackTrace();
            }

            reader.seek(0);
            load = reader.readLine();
            reader.close();

            toks = load.split(" +");

            long idle2 = Long.parseLong(toks[4]);
            long cpu2 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5])
                    + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
            return (float) (cpu2 - cpu1) / ((cpu2 + idle2) - (cpu1 + idle1)) * 100;

        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return 0;
    }
}

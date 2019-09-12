package me.com.sendbyrtp;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.IllegalBlockingModeException;

/**
 * Created by cxg on 2019/9/12.
 */
public class MainActivity extends Activity {

    private SendRingTask sendRingTask;

    private static final String IP = "172.16.100.174";
    private static final int PORT = 51002;

    public static Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = getApplicationContext();

        findViewById(R.id.send_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRingTask = new SendRingTask(IP, PORT, new SendRingTask.SendRingListener() {
                    @Override
                    public void onSendRingError() {
                        Log.e("onSendRingError","startSendRing error return !");
                    }
                });
                sendRingTask.execute();
            }
        });
    }

    public static class SendRingTask extends AsyncTask<Void, Void, Boolean> {

        private SendRingListener sendRingListener;

        private DatagramSocket datagramSocket;
        private DatagramPacket datagramPacket;
        private String ip;
        private int port;

        private InputStream dis = null;

        public SendRingTask(String ip, int port, SendRingListener sendRingListener) {
            this.ip = ip;
            this.port = port;
            this.sendRingListener = sendRingListener;
            try {
                if (dis != null) {
                    dis.close();
                }
                dis = context.getAssets().open("ring_start_48K.aac");
            } catch (IOException e) {
                e.printStackTrace();
            }
            connectServer();
        }

        public interface SendRingListener {
            void onSendRingError();
        }

        @Override
        protected Boolean doInBackground(Void... params) {

            //Log.e("time0",System.currentTimeMillis()+"");

            byte[] allData = null;
            try {

                if (dis == null){
                    return false;
                }

                //先读出全部数据
                allData = new byte[dis.available()];
                int allLen = dis.read(allData, 0, allData.length);

                dis.close();
                dis = null;

                if (allLen == -1) {
                    Log.e("SendRingTask", "read file failed!");
                    return false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }


            byte[] sendBytes;
            try {
                if (allData == null){
                    return false;
                }
                //第一次读adst头
                for (int ni = 0; ni < allData.length; ni++) {

                    if (isCancelled()) {
                        return true;
                    }

                    if (allData[ni] == -1) {
                        if (allData.length <= ni + 1) {
                            break;
                        }
                        if (allData[ni + 1] == -15) {
                            if (allData.length <= ni + 6) {
                                break;
                            }
                            //有效数据
                            int dataLen = (((((int) allData[ni + 3]) & 0x03) << 11) | (((int) allData[ni + 4] & 0xFF) << 3) | ((int) allData[ni + 5] & 0xE0) >> 5);
                            sendBytes = new byte[dataLen];
                            System.arraycopy(allData, ni, sendBytes, 0, sendBytes.length);

                            sendRtpPackage(sendBytes, sendBytes.length);

                            //跳过部分循环
                            ni = ni + sendBytes.length - 1;

                            if (isCancelled()) {
                                stopSend();
                                return true;
                            } else {
                                try {
                                    Thread.sleep(20);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    return true;
                                }
                            }
                        }

                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            stopSend();


            return true;
        }

        private void stopSend() {
            try {
                if (dis != null) {
                    dis.close();
                    dis = null;
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (datagramSocket != null) {
                datagramSocket.close();
            }

            cancel(true);
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            super.onPostExecute(aBoolean);

            if (datagramSocket != null) {
                datagramSocket.close();
            }
            if (dis != null) {
                try {
                    dis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            sendRingListener.onSendRingError();
        }

        private void connectServer() {
            try {
                datagramSocket = new DatagramSocket();
                datagramSocket.setSoTimeout(3000);
                datagramPacket = new DatagramPacket(new byte[0], 0, InetAddress.getByName(ip), port);
            } catch (SocketException | UnknownHostException e) {
                e.printStackTrace();
            }
        }

        private byte[] sendbuf;
        private int seq_num = 0;
        //private int timestamp_increse = (int) (frequence / 152);//framerate是帧率
        //帧率可以通过采样率除以每秒发送的帧数（帧数可以抓包查看）获取
        private int timestamp_increse = 1024;//framerate是帧率
        private int ts_current = 0;

        /**
         * @param buffer
         * @param bufferReadResult 一个rtp包如果是经过UDP传输的原则上不要超过1460，
         */
        private void sendRtpPackage(byte[] buffer, int bufferReadResult) {

            sendbuf = new byte[bufferReadResult + 12];
            sendbuf[0] = (byte) (sendbuf[0] | 0x80); // 版本号,此版本固定为2
            sendbuf[1] = (byte) ((byte) (0x80 | 101)); //后七位表示载荷类型，第八位表示M位
            sendbuf[11] = 10;//随机指定10，并在本RTP回话中全局唯一,java默认采用网络字节序号 不用转换（同源标识符的最后一个字节）

            if (bufferReadResult <= 15000) {
                sendbuf[3] = (byte) seq_num++;
                System.arraycopy(intToByte(seq_num), 0, sendbuf, 2, 2);//send[2]和send[3]为序列号，共两位
                {
                    // java默认的网络字节序是大端字节序（无论在什么平台上），因为windows为小字节序，所以必须倒序
                    /**参考：
                     * http://blog.csdn.net/u011068702/article/details/51857557
                     * http://cpjsjxy.iteye.com/blog/1591261
                     */
                    byte temp;
                    temp = sendbuf[3];
                    sendbuf[3] = sendbuf[2];
                    sendbuf[2] = temp;
                }

                System.arraycopy(buffer, 0, sendbuf, 12, bufferReadResult);
                ts_current = ts_current + timestamp_increse;
                System.arraycopy(intToByte(ts_current), 0, sendbuf, 4, 4);//序列号接下来是时间戳，4个字节，存储后也需要倒序
                {
                    byte temp;
                    temp = sendbuf[4];
                    sendbuf[4] = sendbuf[7];
                    sendbuf[7] = temp;
                    temp = sendbuf[5];
                    sendbuf[5] = sendbuf[6];
                    sendbuf[6] = temp;
                }

                sendPackageToServer(sendbuf);
            }
        }

        private byte[] intToByte(int number) {
            int temp = number;
            byte[] b = new byte[4];
            for (int i = 0; i < b.length; i++) {
                b[i] = Integer.valueOf(temp & 0xff).byteValue();// 将最低位保存在最低位
                temp = temp >> 8; // 向右移8位
            }
            return b;
        }

        private void sendPackageToServer(byte[] out) {

            if (out != null) {
                try {
                    datagramPacket.setData(out);
                    datagramSocket.send(datagramPacket);
                } catch (IOException | SecurityException | IllegalBlockingModeException | IllegalArgumentException e) {
                    e.printStackTrace();
                }

            }
        }


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sendRingTask != null && sendRingTask.getStatus() == AsyncTask.Status.RUNNING){
            sendRingTask.cancel(true);
        }
    }
}

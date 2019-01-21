package io.binstream.github.demo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.tvbus.engine.TVCore;
import com.tvbus.engine.TVListener;
import com.tvbus.engine.TVService;


public class MainActivity extends Activity {
    public static final String TAG = "MainActivity";

    private static TVCore mTVCore = null;

    private int mBuffer;
    private int mTmPlayerConn;
    private long mMPCheckTime = 0;
    private static String playbackUrl;

    private final static long MP_START_CHECK_INTERVAL = 10 * 1000 * 1000 * 1000L; // 10 second

    private TextView mStatusView;
    private SimpleExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusView = findViewById(R.id.text_status);

        initExoPlayer();

        startTVBusService();

        findViewById(R.id.button_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity.this.loadChannelList();
            }
        });
        loadChannelList();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        stoPlayback();
        stopService(new Intent(this, TVService.class));
    }


    // tvbus p2p module related
    private void startTVBusService() {
        mTVCore = TVCore.getInstance();
        assert mTVCore != null;

        // start tvcore
        mTVCore.setTVListener(new TVListener() {
            @Override
            public void onInited(String result) {
                parseCallbackInfo("onInited", result);
            }

            @Override
            public void onStart(String result) {
                parseCallbackInfo("onStart", result);
            }

            @Override
            public void onPrepared(String result) {
                if(parseCallbackInfo("onPrepared", result)) {
                    startPlayback();
                }
            }

            @Override
            public void onInfo(String result) {
                parseCallbackInfo("onInfo", result);
                checkPlayer();
            }

            @Override
            public void onStop(String result) {
                parseCallbackInfo("onStop", result);
            }

            @Override
            public void onQuit(String result) {
                parseCallbackInfo("onQuit", result);
            }
        });

        startService(new Intent(this, TVService.class));
    }

    private void updateStatusView(String status) {
        final String fStatus = status;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStatusView.setText(fStatus);
            }
        });
    }

    private boolean parseCallbackInfo(String event, String result) {
        JSONObject jsonObj = null;
        String statusMessage = null;

        try {
            jsonObj = new JSONObject(result);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if(jsonObj == null) {
            return false;
        }
        switch (event) {
            case "onInited":
                if ((jsonObj.optInt("tvcore", 1)) == 0) {
                    statusMessage = "Ready to go!";
                }
                else {
                    statusMessage = "Init error!";
                }
                break;

            case "onStart":
                break;

            case "onPrepared": // use http-mpegts as source
                if(jsonObj.optString("http", null) != null) {
                    playbackUrl = jsonObj.optString("http", null);
                    break;
                }

                return false;
            case "onInfo":
                mTmPlayerConn = jsonObj.optInt("hls_last_conn", 0);
                mBuffer = jsonObj.optInt("buffer", 0);

                statusMessage = "" + mBuffer + "  "
                        + jsonObj.optInt("download_rate", 0) * 8 / 1000 +"K";
                break;

            case "onStop":
                if(jsonObj.optInt("errno", 1) < 0) {
                    statusMessage = "stop: " + jsonObj.optInt("errno", 1);
                }
                break;

            case "onQut":
                break;
        }
        if(statusMessage != null) {
            updateStatusView(statusMessage);
        }
        return true;
    }


    private void startChannel(String address, String accessCode) {
        stoPlayback();
        mMPCheckTime = Long.MAX_VALUE;
        mTmPlayerConn = mBuffer = 0;

        if(accessCode == null) {
            mTVCore.start(address);
        }
        else {
            mTVCore.start(address, accessCode);
        }
    }

    // player related
    private void checkPlayer() {
        // Attention
        // check player playing must run in main thread
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mTmPlayerConn > 20 && mBuffer > 50) {
                    stoPlayback();
                }

                if(System.nanoTime() > mMPCheckTime) {
                    int playbackState = player.getPlaybackState();
                    if (! (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)) {
                        startPlayback();
                    }
                }
            }
        });
    }

    private void stoPlayback() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                player.stop();
            }
        });
    }

    private void startPlayback() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMPCheckTime = System.nanoTime() + MP_START_CHECK_INTERVAL;
                DataSource.Factory dataSourceFactory =
                        new DefaultDataSourceFactory(MainActivity.this,"tvbus",null);
                MediaSource videoSource = new ExtractorMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(Uri.parse(playbackUrl));

                player.prepare(videoSource);
                player.setPlayWhenReady(true);
            }
        });
    };


    private void initExoPlayer() {
        PlayerView playerView = findViewById(R.id.exoplayer_view);
        playerView.requestFocus();
        playerView.setControllerAutoShow(false);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);


        DefaultRenderersFactory rendererFactory = new DefaultRenderersFactory(this,
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        TrackSelector trackSelector = new DefaultTrackSelector();
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder();
        builder.setAllocator(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE));
        builder.setBufferDurationsMs(
                2000,
                15000,
                1500,
                0
        );
        LoadControl loadControl = builder.createDefaultLoadControl();


        player = ExoPlayerFactory.newSimpleInstance(this, rendererFactory, trackSelector, loadControl);
        player.addVideoListener(new com.google.android.exoplayer2.video.VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

            }

            @Override
            public void onRenderedFirstFrame() {
                mMPCheckTime = System.nanoTime();
            }
        });

        playerView.setPlayer(player);
    }


    // Channel list related
    private void loadChannelList() {
        ((ListView) findViewById(R.id.list_view)).setAdapter(null);

        new AsyncTask<Void, Void, List> () {

            @Override
            protected List doInBackground(Void... voids) {
                return parseChannels(getChannels());
            }

            private List parseChannels(String channelsStr) {
                if(channelsStr == null) {
                    return null;
                }

                try {
                    ArrayList<HashMap<String, Object>> channelList = new ArrayList<HashMap<String, Object>>();

                    JSONArray array = new JSONArray(channelsStr);

                    for(int i = 0; i < array.length(); i ++) {
                        HashMap<String, Object> map = new HashMap<String, Object>();
                        map.put("name", array.getJSONObject(i).getString("name"));
                        map.put("address", array.getJSONObject(i).getString("address"));
                        map.put("from", array.getJSONObject(i).getString("from"));
                        map.put("type", array.getJSONObject(i).getString("type"));
                        channelList.add(map);
                    }

                    return channelList;
                } catch (JSONException e) {
                    e.printStackTrace();

                    return null;
                }
            }

            private String getChannels() {
                URL url;
                try {
                    url = new URL("http://chlist.sopplus.tv/v2/channels");
                } catch (MalformedURLException e) {
                    Log.d(TAG, e.toString());
                    return null;
                }

                HttpURLConnection urlConnection = null;
                InputStream inputStream = null;
                String content = null;
                try {
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setConnectTimeout(15000);
                    urlConnection.setReadTimeout(10000);

                    inputStream = urlConnection.getInputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder sb = new StringBuilder();
                    String output;

                    if (urlConnection.getResponseCode() != 200) {
                        return null;
                    }

                    while ((output = br.readLine()) != null) {
                        sb.append(output);
                        content = sb.toString();
                    }
                } catch (Exception e) {
                    Log.d(TAG, e.toString());
                    return null;
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (Exception ignore) {
                        }
                    }
                }

                return content;
            }

            @Override
            protected void onPostExecute(List channelList) {
                super.onPostExecute(channelList);

                refreshChannelList(channelList);
            }

            private void refreshChannelList(List channelList) {
                if(channelList == null) {
                    return;
                }
                final List fList = channelList;

                ListView list = findViewById(R.id.list_view);

                SimpleAdapter listItemAdapter = new SimpleAdapter(MainActivity.this,
                        channelList,
                        R.layout.list_items,
                        new String[] { "name", "from" },
                        new int[] { R.id.ItemTitle, R.id.ItemText }
                );

                list.setAdapter(listItemAdapter);


                list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                        HashMap<String, Object> chInfo = (HashMap<String, Object>)fList.get(arg2);

                        String type = chInfo.get("type").toString();
                        String address = chInfo.get("address").toString();

                        if("private".equals(type)) {
                            // set the accessCode from user input
                            startChannel(address, "1111");
                        }
                        else {
                            startChannel(address, null);
                        }
                    }
                });
            }
        }.execute();
    }
}


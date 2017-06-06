package com.tvbus.engine;

/**
 * Created by sopdev on 15/10/13.
 * VAN
 */
public interface TVListener {
    public void onInited(String result);
    public void onStart(String result);
    public void onPrepared(String result);
    public void onInfo(String result);
    public void onStop(String result);
    public void onQuit(String result);
}

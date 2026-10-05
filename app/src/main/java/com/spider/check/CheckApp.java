package com.spider.check;

import android.app.Application;
import android.content.Context;

import com.github.catvod.Init;

public class CheckApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }
}

/*
 * Copyright (c) 2015 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.device;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;

import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.System;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class SamsungDozeService extends Service {
    private static final String TAG = "SamsungDozeService";
    private static final boolean DEBUG = false;

    private static final String DOZE_INTENT = "com.android.systemui.doze.pulse";

    private static final String GESTURE_HAND_WAVE_KEY = "gesture_hand_wave";
    private static final String GESTURE_POCKET_KEY = "gesture_pocket";
    private static final String PROXIMITY_WAKE_KEY = "proximity_wake_enable";

    private static final int POCKET_DELTA_NS = 1000 * 1000 * 1000;

    private AlarmManager mAlarmManager;
    private Context mContext;
    private SamsungProximitySensor mSensor;
    private PowerManager mPowerManager;
    private WifiManager mWifiManager;
    private PendingIntent mPendingIntent;

    private boolean mHandwaveGestureEnabled = false;
    private boolean mPocketGestureEnabled = false;
    private boolean mProximityWakeEnabled = false;
    private int mIsWifiEnabledByUser = 0;
    private long mWifiInitialWakeupInterval = 5 * 60 * 1000;
    private long mWifiWakeupInterval = 10 * 60 * 1000;
    private long mWifiEnableInterval = 20 * 1000;

    class SamsungProximitySensor implements SensorEventListener {
        private SensorManager mSensorManager;
        private Sensor mSensor;

        private boolean mSawNear = false;
        private long mInPocketTime = 0;

        public SamsungProximitySensor(Context context) {
            mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            boolean isNear = event.values[0] < mSensor.getMaximumRange();
            if (mSawNear && !isNear) {
                if (shouldPulse(event.timestamp)) {
                    launchDozePulse();
                }
            } else {
                mInPocketTime = event.timestamp;
            }
            mSawNear = isNear;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            /* Empty */
        }

        private boolean shouldPulse(long timestamp) {
            long delta = timestamp - mInPocketTime;

            if (mHandwaveGestureEnabled && mPocketGestureEnabled) {
                return true;
            } else if (mProximityWakeEnabled && (delta < POCKET_DELTA_NS)) {
                mPowerManager.wakeUp(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
                return false;
            } else if (mHandwaveGestureEnabled && !mPocketGestureEnabled) {
                return delta < POCKET_DELTA_NS;
            } else if (!mHandwaveGestureEnabled && mPocketGestureEnabled) {
                return delta >= POCKET_DELTA_NS;
            }
            return false;
        }

        public void testAndEnable() {
            if ((isDozeEnabled() && (mHandwaveGestureEnabled || mPocketGestureEnabled)) ||
                    mProximityWakeEnabled) {
                mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        public void disable() {
            mSensorManager.unregisterListener(this, mSensor);
        }
    }

    private int getWifiState() {
        try {
            return mWifiManager.getWifiState();
        } catch (Exception e) {
            Log.e(TAG, "Caught an exception while getting wifi state", e);
        }

        return 0;
    }

    private void setWifiState(boolean state) {
        try {
            mWifiManager.setWifiEnabled(state);
        } catch (Exception e) {
            Log.e(TAG, "Caught an exception while setting wifi state", e);
        }
    }

    public void toggleWifi() {
        Log.d(TAG, "Enabling wifi");
        setWifiState(true);
        try {
            Thread.sleep(mWifiEnableInterval);
        } catch (InterruptedException e) {
            Log.d(TAG, "Caught an interrupt while sleeping", e);
        }
        Log.d(TAG, "Disabling wifi");
        setWifiState(false);
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "SamsungDozeService Started");
        mContext = this;
        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mSensor = new SamsungProximitySensor(mContext);
        mWifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        mIsWifiEnabledByUser = getWifiState();
        mAlarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        loadPreferences(sharedPrefs);
        sharedPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        if (!isInteractive()) {
            mSensor.testAndEnable();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenStateReceiver, screenStateFilter);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void launchDozePulse() {
        mContext.sendBroadcast(new Intent(DOZE_INTENT));
    }

    private boolean isInteractive() {
        return mPowerManager.isInteractive();
    }

    private boolean isDozeEnabled() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, 1) != 0;
    }

    private void onDisplayOn() {
        if (DEBUG) Log.d(TAG, "Display on");
        mSensor.disable();

        try {

        if (mIsWifiEnabledByUser == WifiManager.WIFI_STATE_ENABLED) {
            Log.d(TAG, "Turning Wifi on");

            Intent intent = new Intent(mContext, AlarmReceiver.class);
            mPendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_NO_CREATE);
            if (mPendingIntent != null && mAlarmManager != null) {
                mAlarmManager.cancel(mPendingIntent);
            }
            setWifiState(true);
        }
        } catch (Exception e) {
            Log.e(TAG, "Caught an exception while display off", e);
        }

    }

    private void onDisplayOff() {
        if (DEBUG) Log.d(TAG, "Display off");
        mSensor.testAndEnable();

        mIsWifiEnabledByUser = getWifiState();

        try {

        if (mIsWifiEnabledByUser == WifiManager.WIFI_STATE_ENABLED) {
            Log.d(TAG, "Turning Wifi off");

            //mPendingIntent = PendingIntent.getBroadcast(mContext, 0, mIntent, 0);
            Intent intent = new Intent(mContext, AlarmReceiver.class);
            mPendingIntent = PendingIntent.getService(this, 0, intent, 0);
            mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + mWifiInitialWakeupInterval,
                mWifiWakeupInterval, mPendingIntent);
            setWifiState(false);
        }

        } catch (Exception e) {
            Log.e(TAG, "Caught an exception while display off", e);
        }

    }

    private void loadPreferences(SharedPreferences sharedPreferences) {
        mHandwaveGestureEnabled = sharedPreferences.getBoolean(GESTURE_HAND_WAVE_KEY, false);
        mPocketGestureEnabled = sharedPreferences.getBoolean(GESTURE_POCKET_KEY, false);
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                onDisplayOff();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                onDisplayOn();
            }
        }
    };

    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (GESTURE_HAND_WAVE_KEY.equals(key)) {
                mHandwaveGestureEnabled = sharedPreferences.getBoolean(GESTURE_HAND_WAVE_KEY, false);
            } else if (GESTURE_POCKET_KEY.equals(key)) {
                mPocketGestureEnabled = sharedPreferences.getBoolean(GESTURE_POCKET_KEY, false);
            } else if (PROXIMITY_WAKE_KEY.equals(key)) {
                mProximityWakeEnabled = sharedPreferences.getBoolean(PROXIMITY_WAKE_KEY, false);
            }
        }
    };
}

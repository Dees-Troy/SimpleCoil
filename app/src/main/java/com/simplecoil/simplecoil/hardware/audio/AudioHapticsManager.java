/*
 * Copyright (C) 2026 Ethan Yonker
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

package com.simplecoil.simplecoil.hardware.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import com.simplecoil.simplecoil.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages preloaded SoundPool audio playback and haptic vibrator feedback
 * for gameplay events (shots, hits, eliminations, reloads, spawns).
 */
public class AudioHapticsManager {
    private static final String TAG = "AudioHapticsManager";
    public static final long DEFAULT_VIBRATE_DURATION_MS = 250;

    private Context context;
    private SoundPool soundPool;
    private Vibrator vibrator;

    private final Map<Integer, Integer> soundMap = new HashMap<>();
    private boolean isLoaded = false;

    public enum SoundEffect {
        SHOT(R.raw.shootingshort),
        EMPTY(R.raw.empty),
        RELOAD(R.raw.reload),
        HIT(R.raw.hit),
        ELIMINATED(R.raw.eliminated),
        SPAWN(R.raw.spawn),
        BEEP(R.raw.beep),
        SCORE(R.raw.score),
        HIT_CONFIRM(R.raw.hit_confirm),
        HIT_ELIMINATED(R.raw.hit_eliminated),
        HIT_SHIELDED(R.raw.hit_shielded),
        GAME_END(R.raw.game_end);

        private final int rawResId;

        SoundEffect(int rawResId) {
            this.rawResId = rawResId;
        }

        public int getRawResId() {
            return rawResId;
        }
    }

    public AudioHapticsManager() {}

    public synchronized void initialize(Context context) {
        if (context == null) return;
        this.context = context.getApplicationContext();

        initVibrator(this.context);
        initSoundPool(this.context);
    }

    private void initVibrator(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null) {
                    vibrator = vibratorManager.getDefaultVibrator();
                }
            } else {
                vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Vibrator: " + e.getMessage());
        }
    }

    private void initSoundPool(Context ctx) {
        if (soundPool != null) {
            return;
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build();

        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                isLoaded = true;
            } else {
                Log.w(TAG, "Sound load failed for sample ID: " + sampleId + " with status: " + status);
            }
        });

        for (SoundEffect effect : SoundEffect.values()) {
            int soundId = soundPool.load(ctx, effect.getRawResId(), 1);
            soundMap.put(effect.getRawResId(), soundId);
        }
    }

    public void playSound(SoundEffect effect) {
        if (effect == null) return;
        playSound(effect.getRawResId());
    }

    public void playSound(int rawResId) {
        if (soundPool != null && soundMap.containsKey(rawResId)) {
            Integer soundId = soundMap.get(rawResId);
            if (soundId != null) {
                soundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
                return;
            }
        }

        // Fallback to MediaPlayer if SoundPool isn't ready
        playFallbackSound(rawResId);
    }

    private void playFallbackSound(int rawResId) {
        if (context == null) return;
        try {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, rawResId);
            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener(MediaPlayer::release);
                mediaPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing fallback sound: " + e.getMessage());
        }
    }

    public void playShootSound() { playSound(SoundEffect.SHOT); }
    public void playEmptySound() { playSound(SoundEffect.EMPTY); }
    public void playReloadSound() { playSound(SoundEffect.RELOAD); }
    public void playHitSound() { playSound(SoundEffect.HIT); }
    public void playEliminatedSound() { playSound(SoundEffect.ELIMINATED); }
    public void playSpawnSound() { playSound(SoundEffect.SPAWN); }
    public void playBeepSound() { playSound(SoundEffect.BEEP); }
    public void playScoreSound() { playSound(SoundEffect.SCORE); }

    public void vibrate(long durationMs) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering vibration: " + e.getMessage());
        }
    }

    public void playHitFeedback() {
        vibrate(DEFAULT_VIBRATE_DURATION_MS);
        playHitSound();
    }

    public void playEliminatedFeedback() {
        vibrate(DEFAULT_VIBRATE_DURATION_MS);
        playEliminatedSound();
    }

    public synchronized void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundMap.clear();
        isLoaded = false;
    }
}

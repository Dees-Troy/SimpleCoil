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

package com.simplecoil.simplecoil.hardware.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.mousebird.maply.ComponentObject;
import com.mousebird.maply.MapController;
import com.mousebird.maply.MaplyBaseController;
import com.mousebird.maply.MarkerInfo;
import com.mousebird.maply.Point2d;
import com.mousebird.maply.ScreenMarker;
import com.simplecoil.simplecoil.Globals;
import com.simplecoil.simplecoil.R;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import java.util.Map;

/**
 * Encapsulates GPS location updates, location provider comparison logic,
 * and Maply screen marker rendering for player positions.
 */
public class MapLocationManager {
    private static final String TAG = "MapLocationManager";

    public static final double ZOOM_LEVEL = 0.00003;
    public static final double PI180 = Math.PI / 180;
    public static final int TWO_MINUTES = 1000 * 60 * 2;

    private static volatile MapLocationManager instance;

    private Location currentBestLocation;
    private LocationManager locationManager;
    private LocationListener locationListener;

    private double lastLongitude = 0;
    private double lastLatitude = 0;

    private MapLocationManager() {}

    public static MapLocationManager getInstance() {
        if (instance == null) {
            synchronized (MapLocationManager.class) {
                if (instance == null) {
                    instance = new MapLocationManager();
                }
            }
        }
        return instance;
    }

    public synchronized void startLocationUpdates(Context context, LocationUpdateListener listener) {
        if (context == null) return;

        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }

        if (locationManager == null) return;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) {
                listener.onPermissionRequired();
            }
            return;
        }

        if (locationListener == null) {
            lastLongitude = 0;
            lastLatitude = 0;

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null) return;
                    if (isBetterLocation(location, currentBestLocation)) {
                        currentBestLocation = location;
                        lastLatitude = location.getLatitude();
                        lastLongitude = location.getLongitude();
                        if (listener != null) {
                            listener.onLocationUpdated(location, lastLatitude, lastLongitude);
                        }
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            };

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 1, locationListener);
            currentBestLocation = getLastBestLocation(context);
            if (currentBestLocation != null && listener != null) {
                listener.onLocationUpdated(currentBestLocation, currentBestLocation.getLatitude(), currentBestLocation.getLongitude());
            }
        }
    }

    public synchronized void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }

    public Location getCurrentBestLocation() {
        return currentBestLocation;
    }

    public Location getLastBestLocation(Context context) {
        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) return null;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        Location locationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        long gpsTime = (locationGPS != null) ? locationGPS.getTime() : 0;
        long netTime = (locationNet != null) ? locationNet.getTime() : 0;

        return (gpsTime - netTime > 0) ? locationGPS : locationNet;
    }

    public boolean isBetterLocation(Location location, Location currentBest) {
        if (location == null) return false;
        if (currentBest == null) return true;

        return isBetterLocation(
                location.getTime(), location.getAccuracy(), location.getProvider(),
                currentBest.getTime(), currentBest.getAccuracy(), currentBest.getProvider()
        );
    }

    public boolean isBetterLocation(long time, float accuracy, String provider,
                                   long currentTime, float currentAccuracy, String currentProvider) {
        long timeDelta = time - currentTime;
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) {
            return true;
        } else if (isSignificantlyOlder) {
            return false;
        }

        int accuracyDelta = (int) (accuracy - currentAccuracy);
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        boolean isFromSameProvider = isSameProvider(provider, currentProvider);

        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
    }

    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    // Maply Screen Marker Helpers

    public void insertYourMarker(MapController mapControl, Context context, Location location, int playerId, ComponentObject[] playerMarkers) {
        if (mapControl == null || context == null || location == null || playerMarkers == null) return;
        removeYourMarker(mapControl, playerId, playerMarkers);

        MarkerInfo markerInfo = new MarkerInfo();
        Bitmap icon = BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_gps_you);
        Point2d markerSize = new Point2d(72, 72);

        ScreenMarker you = new ScreenMarker();
        you.loc = Point2d.FromDegrees(location.getLongitude(), location.getLatitude());
        you.image = icon;
        you.size = markerSize;

        if (playerId >= 0 && playerId < playerMarkers.length) {
            playerMarkers[playerId] = mapControl.addScreenMarker(you, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
        }

        mapControl.setPositionGeo(location.getLongitude() * PI180, location.getLatitude() * PI180, ZOOM_LEVEL);
        mapControl.currentMapZoom(Point2d.FromDegrees(location.getLongitude(), location.getLatitude()));
        mapControl.setZoomLimits(ZOOM_LEVEL, ZOOM_LEVEL);
    }

    public void removeYourMarker(MapController mapControl, int playerId, ComponentObject[] playerMarkers) {
        removePlayerMarker(mapControl, playerId, playerMarkers);
    }

    public void insertPlayerMarkers(MapController mapControl, Context context, Map<Byte, Globals.GPSData> gpsDataMap, int currentPlayerId, ComponentObject[] playerMarkers) {
        if (mapControl == null || context == null || gpsDataMap == null || playerMarkers == null) return;

        MarkerInfo markerInfo = new MarkerInfo();
        Point2d markerSize = new Point2d(72, 72);

        PlayerRepository playerRepo = PlayerRepository.getInstance();
        GameSettingsRepository settingsRepo = GameSettingsRepository.getInstance();

        int currentTeam = -1;
        if (settingsRepo.getGameMode() != GameSettingsRepository.GAME_MODE_FFA) {
            currentTeam = playerRepo.calcNetworkTeam((byte) currentPlayerId);
        }

        Bitmap teammateIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_gps_teammate);
        Bitmap enemyIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_gps_enemy);

        for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
            if (x == currentPlayerId) continue;
            Byte pid = (byte) x;
            if (gpsDataMap.containsKey(pid)) {
                Globals.GPSData data = gpsDataMap.get(pid);
                if (data != null) {
                    boolean isTeammate = (currentTeam != -1 && data.team == currentTeam);
                    boolean isAllowed = isTeammate || (settingsRepo.getGpsMode() == GameSettingsRepository.GPS_ALL);
                    if (!isAllowed) {
                        removePlayerMarker(mapControl, x, playerMarkers);
                        data.hasUpdate = false;
                    } else if (data.hasUpdate || (x >= 0 && x < playerMarkers.length && playerMarkers[x] == null)) {
                        data.hasUpdate = false;
                        removePlayerMarker(mapControl, x, playerMarkers);

                        ScreenMarker playerMarker = new ScreenMarker();
                        playerMarker.loc = Point2d.FromDegrees(data.longitude, data.latitude);
                        playerMarker.size = markerSize;
                        playerMarker.image = isTeammate ? teammateIcon : enemyIcon;
                        if (x >= 0 && x < playerMarkers.length) {
                            playerMarkers[x] = mapControl.addScreenMarker(playerMarker, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
                        }
                    }
                }
            } else {
                removePlayerMarker(mapControl, x, playerMarkers);
            }
        }
    }

    public void removePlayerMarker(MapController mapControl, int playerId, ComponentObject[] playerMarkers) {
        if (mapControl == null || playerMarkers == null) return;
        if (playerId >= 0 && playerId < playerMarkers.length && playerMarkers[playerId] != null) {
            mapControl.removeObject(playerMarkers[playerId], MaplyBaseController.ThreadMode.ThreadCurrent);
            playerMarkers[playerId] = null;
        }
    }

    public void removeAllPlayerMarkers(MapController mapControl, ComponentObject[] playerMarkers) {
        if (playerMarkers == null) return;
        for (int i = 0; i < playerMarkers.length; i++) {
            removePlayerMarker(mapControl, i, playerMarkers);
        }
    }
}

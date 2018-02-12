/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.mousebird.maply.ComponentObject;
import com.mousebird.maply.GlobeMapFragment;
import com.mousebird.maply.MapController;
import com.mousebird.maply.MaplyBaseController;
import com.mousebird.maply.MarkerInfo;
import com.mousebird.maply.Point2d;
import com.mousebird.maply.Point3d;
import com.mousebird.maply.QuadImageTileLayer;
import com.mousebird.maply.RemoteTileInfo;
import com.mousebird.maply.RemoteTileSource;
import com.mousebird.maply.ScreenMarker;
import com.mousebird.maply.SphericalMercatorCoordSystem;

import java.io.File;
import java.util.Map;

public class MapFragment extends GlobeMapFragment {
    private static final String TAG = "map";

    private Location currentBestLocation = null;
    private LocationManager mLocationManager = null;
    private static final double ZOOM_LEVEL = 0.00003;
    private static final double PI180 = Math.PI / 180;

    private static final int TWO_MINUTES = 1000 * 60 * 2;

    private LocationListener mLocationListener = null;

    private static double mLongitude = 0;
    private static double mLatitude = 0;

    private void sendLocation(Location location) {
        if (location == null) return;
        if (location.getLatitude() == mLatitude && location.getLongitude() == mLongitude)
            return;
        mLongitude = location.getLongitude();
        mLatitude = location.getLatitude();
        Intent intent = new Intent(NetMsg.NETMSG_GPSLOCUPDATE);
        intent.putExtra(NetMsg.INTENT_LATITUDE, mLatitude);
        intent.putExtra(NetMsg.INTENT_LONGITUDE, mLongitude);
        if (getActivity() == null) return;
        getActivity().sendBroadcast(intent);
    }

    /**
     * @return the last know best location
     */
    private Location getLastBestLocation() {
        // We already have this permission because of Bluetooth, but Android Studio insists on having this code or it throws an error
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = getActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                return null;
            }
        }
        Location locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Location locationNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

        long GPSLocationTime = 0;
        if (null != locationGPS) { GPSLocationTime = locationGPS.getTime(); }

        long NetLocationTime = 0;

        if (null != locationNet) {
            NetLocationTime = locationNet.getTime();
        }

        if ( 0 < GPSLocationTime - NetLocationTime ) {
            return locationGPS;
        }
        else {
            return locationNet;
        }
    }

    /*---------- Listener class to get coordinates ------------- */
    private class MyLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location loc) {
            String longitude = "Longitude: " + loc.getLongitude();
            Log.v(TAG, longitude);
            String latitude = "Latitude: " + loc.getLatitude();
            Log.v(TAG, latitude);
            makeUseOfNewLocation(loc);

            insertYourMarker();
        }

        @Override
        public void onProviderDisabled(String provider) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
    }

    /**
     * This method modify the last know good location according to the arguments.
     *
     * @param location The possible new location.
     */
    void makeUseOfNewLocation(Location location) {
        if ( isBetterLocation(location, currentBestLocation) ) {
            currentBestLocation = location;
            sendLocation(location);
        }
    }

    /** Determines whether one location reading is better than the current location fix
     * @param location  The new location that you want to evaluate
     * @param currentBestLocation  The current location fix, to which you want to compare the new one.
     */
    protected boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location,
        // because the user has likely moved.
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than two minutes older, it must be worse.
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle inState) {
        super.onCreateView(inflater, container, inState);
        return baseControl.getContentView();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);
        try {
            ViewGroup.LayoutParams params = baseControl.getContentView().getLayoutParams();
            params.height = params.width;
            baseControl.getContentView().setLayoutParams(params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(NetMsg.NETMSG_GPSDATAUPDATE);
        filter.addAction(NetMsg.NETMSG_LISTPLAYERS);
        filter.addAction(NetMsg.NETMSG_GPSSETTING);
        getActivity().registerReceiver(mGPSDataReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mGPSDataReceiver);
    }

    @Override
    protected MapDisplayType chooseDisplayType() {
        return MapDisplayType.Map;
    }

    private final BroadcastReceiver mGPSDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(NetMsg.NETMSG_GPSDATAUPDATE)) {
                if (intent.getBooleanExtra(NetMsg.INTENT_FULLUPDATE, false)) {
                    for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++) {
                        if (x != Globals.getInstance().mPlayerID)
                            removePlayerMarker(x);
                    }
                }
                insertPlayerMarkers();
            } else if (action.equals(NetMsg.NETMSG_LISTPLAYERS) || action.equals(NetMsg.NETMSG_GPSSETTING)) {
                enableGPS(Globals.getInstance().mUseGPS);
            }
        }
    };

    public void enableGPS(boolean enabled) {
        if (enabled) {
            if (mLocationListener == null) {
                mLongitude = 0;
                mLatitude = 0;
                mLocationListener = new MyLocationListener();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int hasLocationPermission = getActivity().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                1);
                        return;
                    }
                }
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 1, mLocationListener);
                currentBestLocation = getLastBestLocation();
                sendLocation(currentBestLocation);
                insertYourMarker();
            }
        } else {
            if (mLocationListener != null) {
                removeAllPlayerMarkers();
                removeYourMarker();
                mLocationManager.removeUpdates(mLocationListener);
                mLocationListener = null;
            }
        }
    }

    @Override
    protected void controlHasStarted() {
        // setup base layer tiles
        String cacheDirName = "empty";
        File cacheDir = new File(getActivity().getCacheDir(), cacheDirName);
        cacheDir.mkdir();
        RemoteTileSource remoteTileSource = new RemoteTileSource(new RemoteTileInfo("http://localhost/", "png", 0, 18));
        remoteTileSource.setCacheDir(cacheDir);
        SphericalMercatorCoordSystem coordSystem = new SphericalMercatorCoordSystem();

        // globeControl is the controller when using MapDisplayType.Globe
        // mapControl is the controller when using MapDisplayType.Map
        QuadImageTileLayer baseLayer = new QuadImageTileLayer(mapControl, coordSystem, remoteTileSource);
        baseLayer.setImageDepth(1);
        baseLayer.setSingleLevelLoading(false);
        baseLayer.setUseTargetZoomLevel(false);
        baseLayer.setCoverPoles(true);
        baseLayer.setHandleEdges(true);

        // add layer and position
        mapControl.addLayer(baseLayer);
        //mapControl.setAllowRotateGesture(true); // need to figure out a rose compass or something so you can tell which way you have rotated
        mapControl.gestureDelegate = this;

        mLocationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);
        mPlayerMarkers = new ComponentObject[Globals.MAX_PLAYER_ID + 1];
        for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++)
            mPlayerMarkers[x] = null;
    }

    @Override
    public void mapDidStopMoving(MapController mapControl,
                                 Point3d[] corners,
                                 boolean userMotion) {
        if (userMotion && currentBestLocation != null)
            mapControl.setPositionGeo(currentBestLocation.getLongitude() * PI180, currentBestLocation.getLatitude() * PI180, ZOOM_LEVEL);
    }

    private ComponentObject[] mPlayerMarkers = null;

    private void insertYourMarker() {
        if (mapControl == null) return;
        removeYourMarker();

        if (currentBestLocation == null) return;
        MarkerInfo markerInfo = new MarkerInfo();
        Bitmap icon = BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_gps_you);
        Point2d markerSize = new Point2d(72, 72);

        ScreenMarker you = new ScreenMarker();
        you.loc = Point2d.FromDegrees(currentBestLocation.getLongitude(), currentBestLocation.getLatitude()); // Longitude, Latitude
        you.image = icon;
        you.size = markerSize;

        mPlayerMarkers[Globals.getInstance().mPlayerID] = mapControl.addScreenMarker(you, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
        mapControl.setPositionGeo(currentBestLocation.getLongitude() * PI180, currentBestLocation.getLatitude() * PI180, ZOOM_LEVEL);

        mapControl.currentMapZoom(Point2d.FromDegrees(currentBestLocation.getLongitude(), currentBestLocation.getLatitude()));
        mapControl.setZoomLimits(ZOOM_LEVEL, ZOOM_LEVEL);
    }

    private void removeYourMarker() {
        if (mapControl == null) return;
        if (mPlayerMarkers[Globals.getInstance().mPlayerID] != null) {
            mapControl.removeObject(mPlayerMarkers[Globals.getInstance().mPlayerID], MaplyBaseController.ThreadMode.ThreadCurrent);
            mPlayerMarkers[Globals.getInstance().mPlayerID] = null;
        }
    }

    private void insertPlayerMarkers() {
        MarkerInfo markerInfo = new MarkerInfo();
        Point2d markerSize = new Point2d(72, 72);
        // Add other players to the map
        int currentTeam = -1;
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA)
            currentTeam = Globals.getInstance().calcNetworkTeam(Globals.getInstance().mPlayerID);
        Bitmap teammate = BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_gps_teammate);
        Bitmap enemy = BitmapFactory.decodeResource(getActivity().getResources(), R.drawable.ic_gps_enemy);
        Globals.getmGPSDataSemaphore();
        for (Map.Entry<Byte, Globals.GPSData> entry : Globals.getInstance().mGPSData.entrySet()) {
            if (entry.getKey() != Globals.getInstance().mPlayerID && entry.getValue().hasUpdate) {
                entry.getValue().hasUpdate = false;
                removePlayerMarker(entry.getKey());
                ScreenMarker player = new ScreenMarker();
                player.loc = Point2d.FromDegrees(entry.getValue().longitude, entry.getValue().latitude);
                player.size = markerSize;
                if (entry.getValue().team == currentTeam) {
                    player.image = teammate;
                    mPlayerMarkers[entry.getKey()] = mapControl.addScreenMarker(player, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
                } else if (Globals.getInstance().mGPSMode == Globals.GPS_ALL) {
                    player.image = enemy;
                    mPlayerMarkers[entry.getKey()] = mapControl.addScreenMarker(player, markerInfo, MaplyBaseController.ThreadMode.ThreadCurrent);
                }
            }
        }
        Globals.getInstance().mGPSDataSemaphore.release();
    }

    private void removePlayerMarker(int playerID) {
        if (mapControl == null) return;
        if (mPlayerMarkers[playerID] != null)
            mapControl.removeObject(mPlayerMarkers[playerID], MaplyBaseController.ThreadMode.ThreadCurrent);
        mPlayerMarkers[playerID] = null;
    }

    private void removeAllPlayerMarkers() {
        for (int x = 0; x <= Globals.MAX_PLAYER_ID; x++)
            removePlayerMarker(x);
    }
}

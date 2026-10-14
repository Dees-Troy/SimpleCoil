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
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;

import com.mousebird.maply.ComponentObject;
import com.mousebird.maply.GlobeMapFragment;
import com.mousebird.maply.MapController;
import com.mousebird.maply.Point3d;
import com.mousebird.maply.QuadImageTileLayer;
import com.mousebird.maply.RemoteTileInfo;
import com.mousebird.maply.RemoteTileSource;
import com.mousebird.maply.SphericalMercatorCoordSystem;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.hardware.location.LocationUpdateListener;
import com.simplecoil.simplecoil.hardware.location.MapLocationManager;
import com.simplecoil.simplecoil.network.ClientEvent;

import java.io.File;

/**
 * The in-game map. Location tracking and marker drawing live in [MapLocationManager]; this
 * fragment owns the map view, asks for the location permission and tells the network about our
 * position.
 */
public class MapFragment extends GlobeMapFragment {
    private static final String TAG = "map";
    private static final int LOCATION_PERMISSION_REQUEST = 1;

    private MapLocationManager mLocation;
    private PlayerRepository mPlayers;
    private GameSettingsRepository mSettings;
    private boolean mTracking = false;
    private ComponentObject[] mPlayerMarkers = null;

    private static double mLongitude = 0;
    private static double mLatitude = 0;

    private final LocationUpdateListener mLocationListener = new LocationUpdateListener() {
        @Override
        public void onLocationUpdated(Location location, double latitude, double longitude) {
            Log.v(TAG, "Longitude: " + longitude + ", Latitude: " + latitude);
            sendLocation(location);
            insertYourMarker();
        }

        @Override
        public void onPermissionRequired() {
            mTracking = false;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
    };

    private void sendLocation(Location location) {
        if (location == null) return;
        if (location.getLatitude() == mLatitude && location.getLongitude() == mLongitude)
            return;
        mLongitude = location.getLongitude();
        mLatitude = location.getLatitude();
        if (getActivity() == null) return;
        container().getNetworkGateway().sendLocation(mLatitude, mLongitude);
    }

    private AppContainer container() {
        return ((SimpleCoilApp) requireActivity().getApplication()).getContainer();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContainer container = container();
        mLocation = container.getMapLocation();
        mPlayers = container.getPlayers();
        mSettings = container.getSettings();
        // While resumed, as the broadcast receiver it replaces was registered
        container.getClientEvents().observe(this, Lifecycle.State.RESUMED, this::onClientEvent);
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
    public void onDestroy() {
        // The manager is process-wide; don't leave it reporting to a dead fragment.
        if (mTracking) {
            mLocation.stopLocationUpdates();
            mTracking = false;
        }
        super.onDestroy();
    }

    @Override
    protected MapDisplayType chooseDisplayType() {
        return MapDisplayType.Map;
    }

    private void onClientEvent(ClientEvent event) {
        if (event instanceof ClientEvent.GpsData) {
            if (((ClientEvent.GpsData) event).getFullUpdate()) {
                byte me = mPlayers.getPlayerID();
                for (int x = 0; x <= GameSettingsRepository.MAX_PLAYER_ID; x++) {
                    if (x != me)
                        mLocation.removePlayerMarker(mapControl, x, mPlayerMarkers);
                }
            }
            insertPlayerMarkers();
        } else if (event instanceof ClientEvent.ListPlayers || event == ClientEvent.GpsSettingChanged.INSTANCE) {
            enableGPS(mSettings.isUseGPS());
        }
    }

    public void enableGPS(boolean enabled) {
        if (getActivity() == null) return;
        if (enabled) {
            if (!mTracking) {
                mTracking = true;
                mLongitude = 0;
                mLatitude = 0;
                mLocation.startLocationUpdates(getActivity(), mLocationListener);
                if (!mTracking) return; // waiting for the permission
            }
            insertYourMarker();
            insertPlayerMarkers();
        } else {
            mLocation.removeAllPlayerMarkers(mapControl, mPlayerMarkers);
            removeYourMarker();
            if (mTracking) {
                mLocation.stopLocationUpdates();
                mTracking = false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableGPS(mSettings.isUseGPS());
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

        mPlayerMarkers = new ComponentObject[GameSettingsRepository.MAX_PLAYER_ID + 1];
    }

    @Override
    public void mapDidStopMoving(MapController mapControl,
                                 Point3d[] corners,
                                 boolean userMotion) {
        Location best = mLocation.getCurrentBestLocation();
        if (userMotion && best != null)
            mapControl.setPositionGeo(best.getLongitude() * MapLocationManager.PI180, best.getLatitude() * MapLocationManager.PI180, MapLocationManager.ZOOM_LEVEL);
    }

    private void insertYourMarker() {
        if (getActivity() == null) return;
        Location best = mLocation.getCurrentBestLocation();
        if (best == null) {
            removeYourMarker();
            return;
        }
        mLocation.insertYourMarker(mapControl, getActivity(), best, mPlayers.getPlayerID(), mPlayerMarkers);
    }

    private void removeYourMarker() {
        mLocation.removeYourMarker(mapControl, mPlayers.getPlayerID(), mPlayerMarkers);
    }

    private void insertPlayerMarkers() {
        if (getActivity() == null) return;
        mLocation.insertPlayerMarkers(mapControl, getActivity(), mPlayerMarkers);
    }
}

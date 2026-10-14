package com.mousebird.maply;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class GlobeMapFragment extends Fragment {
    public enum MapDisplayType {
        Globe,
        Map
    }

    protected MapController mapControl = new MapController();
    protected MaplyBaseController baseControl = mapControl;

    protected MapDisplayType chooseDisplayType() {
        return MapDisplayType.Map;
    }

    protected void controlHasStarted() {}

    public void mapDidStopMoving(MapController mapControl, Point3d[] corners, boolean userMotion) {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (getContext() != null) {
            mapControl.init(getContext());
        }
        controlHasStarted();
        return baseControl.getContentView();
    }
}

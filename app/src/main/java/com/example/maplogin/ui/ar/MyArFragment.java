package com.example.maplogin.ui.ar;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentOnAttachListener;
import androidx.lifecycle.MediatorLiveData;

import com.example.maplogin.R;
import com.example.maplogin.databinding.FragmentMyArBinding;
import com.example.maplogin.models.ShopItem;
import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.rendering.CameraStream;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.BaseArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class MyArFragment extends Fragment implements
        FragmentOnAttachListener,
        BaseArFragment.OnTapArPlaneListener,
        BaseArFragment.OnSessionConfigurationListener,
        ArFragment.OnViewCreatedListener {

    private FragmentMyArBinding binding;
    private Activity mActivity;
    private ArFragment arFragment;
    private Renderable model;

    private enum AppAnchorState {
        NONE,
        HOSTING,
        HOSTED
    }

    private Anchor anchor;
    private AppAnchorState appAnchorState = AppAnchorState.NONE;
    private boolean isPlaced = false;
    private MultiArViewModel viewModel;
    private MediatorLiveData<ShopItem> currentItemLiveData;
    private MediatorLiveData<HashMap<String, ArViewModel.ShopItemExt>> ownedItemsLiveData;
    MediatorLiveData<HashMap<String, MultiArViewModel.AnchorExt>> anchorsInfoLiveData;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mActivity = getActivity();
        getChildFragmentManager().addFragmentOnAttachListener(this);

        viewModel = new MultiArViewModel();
        // setup load model when select model
        currentItemLiveData = viewModel.getCurrentItemLiveData();
        currentItemLiveData.observe(this, currentItem -> {
            loadModels(currentItem.model);
        });

        // load all anchor into user view
        anchorsInfoLiveData = viewModel.getAnchorsInfoLiveData();
        anchorsInfoLiveData.observe(this, anchors -> {
            if (arFragment.getArSceneView().getSession() == null) {
                Toast.makeText(mActivity, "Load session failed.", Toast.LENGTH_SHORT).show();
                return;
            }
            for (Map.Entry<String, MultiArViewModel.AnchorExt> anchor: anchors.entrySet()) {
                Anchor resolvedAnchor = arFragment.getArSceneView().getSession().resolveCloudAnchor(anchor.getKey());
                String modelUrl = anchor.getValue().modelID;
                loadAndPlaceModel(modelUrl, resolvedAnchor);
            }
        });
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .add(R.id.arFragment, ArFragment.class, null)
                    .commit();
        }
        binding = FragmentMyArBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onAttachFragment(@NonNull FragmentManager fragmentManager, @NonNull Fragment fragment) {
        if (fragment.getId() == R.id.arFragment) {
            arFragment = (ArFragment) fragment;
            arFragment.setOnSessionConfigurationListener(this);
            arFragment.setOnViewCreatedListener(this);
            arFragment.setOnTapArPlaneListener(this);
        }
    }

    @Override
    public void onSessionConfiguration(Session session, Config config) {
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        }
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
    }

    @Override
    public void onViewCreated(ArSceneView arSceneView) {
        arFragment.setOnViewCreatedListener(null);
        arSceneView.getCameraStream()
                .setDepthOcclusionMode(CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED);
        subscribeListeners();
    }

    private void loadAndPlaceModel(String modelUrl, Anchor anchor) {
        ModelRenderable.builder()
                .setSource(mActivity, Uri.parse(modelUrl))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept(model -> {
                    placeModel(anchor, model);
                })
                .exceptionally(throwable -> {
                    Toast.makeText(
                            mActivity, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });
    }

    public void loadModels(String modelUrl) {
        WeakReference<MyArFragment> weakFragment = new WeakReference<>(this);
        ModelRenderable.builder()
                .setSource(mActivity, Uri.parse(modelUrl))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept(model -> {
                    MyArFragment fragment = weakFragment.get();
                    if (fragment != null) {
                        fragment.model = model;
                    }
                })
                .exceptionally(throwable -> {
                    Toast.makeText(
                            mActivity, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });
    }

    @Override
    public void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
        if (model == null) {
            Toast.makeText(mActivity, "Loading...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (arFragment.getArSceneView().getSession() == null) {
            Toast.makeText(mActivity, "Load session failed.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isPlaced) {
            anchor = arFragment.getArSceneView().getSession()
                    .hostCloudAnchor(hitResult.createAnchor());
            appAnchorState = AppAnchorState.HOSTING;
            placeModel(anchor, this.model);
            isPlaced = true;
        }
    }

    private void placeModel(Anchor anchor, Renderable modelRenderable) {
        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());

        // Create the transformable model and add it to the anchor.
        TransformableNode model = new TransformableNode(arFragment.getTransformationSystem());
        model.getScaleController().setMinScale(0.05f);
        model.getScaleController().setMaxScale(1.0f);
        model.setParent(anchorNode);
        model.setRenderable(modelRenderable);
        model.select();
    }

    private void subscribeListeners() {
        arFragment.getArSceneView().getScene().addOnUpdateListener(frameTime -> {
            if (appAnchorState != AppAnchorState.HOSTING)
                return;
            Anchor.CloudAnchorState cloudAnchorState = anchor.getCloudAnchorState();

            if (cloudAnchorState.isError()) {
                Toast.makeText(mActivity, cloudAnchorState.toString(), Toast.LENGTH_SHORT).show();
//                Log.d("hehe", cloudAnchorState.toString());

            } else if (cloudAnchorState == Anchor.CloudAnchorState.SUCCESS) {
                appAnchorState = AppAnchorState.HOSTED;

                String anchorId = anchor.getCloudAnchorId();

                viewModel.placeAnchor(anchorId);

                Toast.makeText(mActivity, "Id: " + anchorId, Toast.LENGTH_SHORT).show();
            }
        });

        Button button = binding.btnResolve;
        button.setOnClickListener(v -> {
            viewModel.selectItem("default");
        });
    }
}

package com.example.sceneformdemo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentOnAttachListener;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Camera;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.SceneView;
import com.google.ar.sceneform.Sceneform;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.BaseArFragment;
import com.google.ar.sceneform.ux.InstructionsController;
import com.google.ar.sceneform.ux.TransformableNode;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements
        FragmentOnAttachListener,
        BaseArFragment.OnSessionConfigurationListener,
        ArFragment.OnViewCreatedListener,
        Scene.OnUpdateListener,
        BaseArFragment.OnTapArPlaneListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int DRAW_ARROW = 0x1100;
    private static final int SAVE_IMAGE = 0x1200;
    private static final int DURATION = 10;
    private boolean alreadyDraw = false;
    private long lastTimeMills = 0;
    private LinkedList<Vector3> points;
    private Handler handler;

    // ARCore 相关变量
    private ArFragment arFragment;
    private ModelRenderable arrowModel;
    private ModelRenderable destModel;
    private ModelRenderable tigerModel;
    private ViewRenderable viewRenderable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alreadyDraw = false;
        lastTimeMills = 0;
        points = new LinkedList<>();
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case DRAW_ARROW: {
                        drawArrows();
                        break;
                    }
                    case SAVE_IMAGE: {
                        screenshot();
//                        saveCameraFrame();
                        break;
                    }
                }
            }
        };

        Button btn = findViewById(R.id.btn_screenshot);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (handler != null) {
                    final int what = SAVE_IMAGE;
                    if (!handler.hasMessages(what)) {
                        handler.sendEmptyMessage(what);
                    }
                }
            }
        });

        // ARCore 相关
        getSupportFragmentManager().addFragmentOnAttachListener(this);
        if (savedInstanceState == null) {
            if (Sceneform.isSupported(this)) {
                getSupportFragmentManager().beginTransaction().add(R.id.ar_fragment, ArFragment.class, null).commit();
            }
        }
        loadModels();
    }

    @Override
    public void onAttachFragment(@NonNull FragmentManager fragmentManager, @NonNull Fragment fragment) {
        if (fragment.getId() == R.id.ar_fragment) {
            arFragment = (ArFragment) fragment;
            arFragment.setOnSessionConfigurationListener(this);
            arFragment.setOnViewCreatedListener(this);
//            arFragment.setOnTapArPlaneListener(this);
        } else {
            Log.e(TAG, "Cannot find fragment");
            showToast("Cannot find fragment");
        }
    }

    @Override
    public void onSessionConfiguration(Session session, Config config) {
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        }
    }

    @Override
    public void onViewCreated(ArSceneView arSceneView) {
        // Fine adjust the maximum frame rate
        arSceneView.setFrameRateFactor(SceneView.FrameRate.FULL);

        // 移除 ArFragment 引导动画
        arFragment.getInstructionsController().setEnabled(false);
        arFragment.getInstructionsController().setView(InstructionsController.TYPE_PLANE_DISCOVERY, null);

        // 禁用平面检测
        arSceneView.getPlaneRenderer().setVisible(false);
        arSceneView.getPlaneRenderer().setEnabled(false);

        // 设置 Camera 的远近平面参数，以控制渲染距离，默认 30m
        arSceneView.getScene().getCamera().setFarClipPlane(50f);

        // 添加相机帧更新监听
        arSceneView.getScene().addOnUpdateListener(this);
    }

    @Override
    public void onUpdate(FrameTime frameTime) {
        arFragment.onUpdate(frameTime);

        if (arFragment.getArSceneView().getArFrame() == null) {
            return;
        }

        TrackingState trackingState = arFragment.getArSceneView().getArFrame().getCamera().getTrackingState();
        Log.i(TAG, "tracking state: " + trackingState);
        if (trackingState != TrackingState.TRACKING) {
            return;
        }

        int size = arFragment.getArSceneView().getScene().getChildren().size();
//        Log.i(TAG, "scene children size: " + size);

        // 渲染箭头
        if (!alreadyDraw) {
            Vector3 worldPosition = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
            Log.i(TAG, String.format("camera world position: [%f, %f, %f]", worldPosition.x, worldPosition.y, worldPosition.z));
            handler.sendEmptyMessage(DRAW_ARROW);
            alreadyDraw = true;
        }

//        // 保存 camera 图像到文件中。不能在当前线程中保存，会导致应用卡顿！
//        long cur = System.currentTimeMillis();
//        if (cur - lastTimeMills >= DURATION * 1000) {
//            int what = SAVE_IMAGE;
//            if (!handler.hasMessages(what)) {
//                handler.sendEmptyMessage(what);
//            }
//            lastTimeMills = cur;
//        }
    }

    @Override
    public void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {

        // Create the Anchor.
        Anchor anchor = hitResult.createAnchor();
        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());

        // Create the transformable model and add it to the anchor.
        TransformableNode model = new TransformableNode(arFragment.getTransformationSystem());
        model.setParent(anchorNode);
        model.setRenderable(this.tigerModel).animate(true).start();
//        model.getScaleController().setMaxScale(5f);
//        model.setLocalScale(Vector3.one().scaled(0.1f));
        model.select();

        Node titleNode = new Node();
        titleNode.setParent(model);
        titleNode.setEnabled(false);
        titleNode.setLocalPosition(new Vector3(0.0f, 1.0f, 0.0f));
        titleNode.setRenderable(viewRenderable);
        titleNode.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (arFragment != null) {
            arFragment.destroy();
        }
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public void loadModels() {
        WeakReference<MainActivity> weakActivity = new WeakReference<>(this);
        ModelRenderable.builder()
//                .setSource(this, Uri.parse("https://storage.googleapis.com/ar-answers-in-search-models/static/Tiger/model.glb"))
                .setSource(this, Uri.parse("models/left_arrow.glb"))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept(model -> {
                    MainActivity activity = weakActivity.get();
                    if (activity != null) {
                        activity.arrowModel = model;
                    }
                }).exceptionally(throwable -> {
                    Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });

        ModelRenderable.builder()
                .setSource(this, Uri.parse("models/destination.glb"))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept(modelRenderable -> {
                    MainActivity activity = weakActivity.get();
                    if (activity != null) {
                        activity.destModel = modelRenderable;
                    }
                }).exceptionally(throwable -> {
                    Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });

        ModelRenderable.builder()
                .setSource(this, Uri.parse("models/tiger.glb"))
                .setIsFilamentGltf(true)
                .setAsyncLoadEnabled(true)
                .build()
                .thenAccept(modelRenderable -> {
                    MainActivity activity = weakActivity.get();
                    if (activity != null) {
                        activity.tigerModel = modelRenderable;
                    }
                }).exceptionally(throwable -> {
                    Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });

        ViewRenderable.builder()
                .setView(this, R.layout.view_model_title)
                .build()
                .thenAccept(viewRenderable -> {
                    MainActivity activity = weakActivity.get();
                    if (activity != null) {
                        activity.viewRenderable = viewRenderable;
                    }
                }).exceptionally(throwable -> {
                    Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG).show();
                    return null;
                });
    }

    /**
     * 渲染箭头模型和目的地模型.
     * 渲染流程: ModelRenderable -> Tracking state -> Anchor -> AnchorNode -> TransformableNode
     */
    private void drawArrows() {

        showToast("正在渲染模型");

        mockData();
        if (points.isEmpty()) {
            return;
        }

        for (int i = 0; i < points.size() - 1; ++i) {
            drawArrow(points.get(i), points.get(i + 1));
        }

        drawDestination(points.getLast());
    }

    /**
     * 按照坐标绘制模型
     *
     * @param from
     * @param to
     */
    private void drawArrow(Vector3 from, Vector3 to) {
        Log.i(TAG, "from: " + from.toString());
        Log.i(TAG, "to: " + to.toString());

        //        Quaternion q = Quaternion.axisAngle(Vector3.up(), 90);
        Vector3 v = Vector3.subtract(to, from);

        // 由于箭头模型初始时是指向左边的，所以此处计算 x轴反方向 与 v 的角度
        Quaternion q = Quaternion.rotationBetweenVectors(Vector3.left(), v);
        float[] translation = {from.x, from.y, from.z};
        float[] rotation = {q.x, q.y, q.z, q.w};
        Pose pose = new Pose(translation, rotation);
        Anchor anchor = arFragment.getArSceneView().getSession().createAnchor(pose);

        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());
//        anchorNode.setWorldPosition(from.position);
//        anchorNode.setWorldRotation(q);

        // 计算 Vector3 与 x 轴正方向的 angle
//        float x_angle = Vector3.angleBetweenVectors(Vector3.right(), v);
//        Log.i(TAG, "angle: " + x_angle + ", " + from.angle);

        TransformableNode transNode = new TransformableNode(arFragment.getTransformationSystem());
        transNode.getScaleController().setEnabled(false);
        transNode.getRotationController().setEnabled(false);
        transNode.getTranslationController().setEnabled(false);

//        transNode.getScaleController().setMaxScale(10f);
        transNode.setLocalScale(Vector3.one().scaled(0.5f));
        transNode.setParent(anchorNode);
        transNode.setRenderable(arrowModel);
//        transNode.select();
    }

    private void drawDestination(Vector3 dest) {
        Log.i(TAG, "draw destination: " + dest.toString());

        Pose pose = Pose.makeTranslation(dest.x, dest.y, dest.z);
        Anchor anchor = arFragment.getArSceneView().getSession().createAnchor(pose);
        Log.i(TAG, "anchor tracking state: " + anchor.getTrackingState());

        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arFragment.getArSceneView().getScene());
//        anchorNode.setWorldPosition(dest.position);
//        anchorNode.setRenderable(flagModel);
//        anchorNode.setOnTapListener((hitTestResult, motionEvent) -> {
//            Node node = hitTestResult.getNode();
//            if (null != node) {
//                node.setSelectable(true);
//            }
//        });

        TransformableNode transNode = new TransformableNode(arFragment.getTransformationSystem());

        transNode.getScaleController().setEnabled(true);
        transNode.getRotationController().setEnabled(true);
        transNode.getTranslationController().setEnabled(false);

        transNode.setParent(anchorNode);
        transNode.setRenderable(destModel);

        transNode.getScaleController().setMaxScale(50f);
        transNode.setLocalScale(Vector3.one().scaled(10.0f));
//        transNode.setWorldScale(Vector3.one().scaled(10.0f));

        transNode.setOnTapListener(((hitTestResult, motionEvent) -> {
            Node node = hitTestResult.getNode();
            if (null != node) {
                node.setSelectable(true);
            }
        }));
    }


    /**
     * 截屏。此方法可以将 3D 模型也保存到图片中
     */
    private void screenshot() {

        showToast("正在截屏");

        ArSceneView view = arFragment.getArSceneView();

        File fileDir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (fileDir != null && !fileDir.exists()) {
            fileDir.mkdirs();
        }

        String filename = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + ".jpg";
        String filePath = new File(fileDir, filename).getAbsolutePath();
        Log.i(TAG, "image path: " + filePath);

        final HandlerThread handlerThread = new HandlerThread("PixelCopier");
        handlerThread.start();

        final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        PixelCopy.request(view, bitmap, (copyResult) -> {
            if (copyResult == PixelCopy.SUCCESS) {
                try {
                    saveBitmapToDisk(bitmap, filePath);
                    Log.i(TAG, "save image success: " + filePath);
                } catch (IOException e) {
                    Toast toast = Toast.makeText(this, e.toString(), Toast.LENGTH_LONG);
                    toast.show();
                    return;
                }
            } else {
                Toast toast = Toast.makeText(this, "截图失败! copyResult=" + copyResult, Toast.LENGTH_LONG);
                toast.show();
            }
            handlerThread.quitSafely();
        }, new Handler(handlerThread.getLooper()));

    }

    private void saveBitmapToDisk(Bitmap bitmap, String filename) throws IOException {

        File out = new File(filename);
        if (!out.getParentFile().exists()) {
            out.getParentFile().mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(filename); ByteArrayOutputStream outputData = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputData);
            outputData.writeTo(outputStream);
            outputStream.flush();
            outputStream.close();
        } catch (IOException ex) {
            throw new IOException("Failed to save bitmap to disk", ex);
        }
    }

    /**
     * 将相机帧数据保存到文件中。此方法只能将相机原始数据保存到文件中，不能将相机中的 3D 模型也保存下来
     */
    void saveCameraFrame() {
        showToast("正在保存图片");

        try {
            Log.i(TAG, String.format("view: width=%d, height=%d", arFragment.getArSceneView().getWidth(), arFragment.getArSceneView().getHeight()));

            Frame arFrame = arFragment.getArSceneView().getArFrame();
            Image image = arFrame.acquireCameraImage();

            Log.i(TAG, String.format("camera image: width=%d, height=%d, timestamp=%d, format=%d", image.getWidth(), image.getHeight(), image.getTimestamp(), image.getFormat()));

            if (image.getFormat() != ImageFormat.YUV_420_888) {
                Log.w(TAG, "image format error: " + image.getFormat());
                return;
            }

            File fileDir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (fileDir != null && !fileDir.exists()) {
                fileDir.mkdirs();
            }
            String filePath = new File(fileDir, image.getTimestamp() + ".png").getAbsolutePath();
            Log.i(TAG, "image path: " + filePath);

            if (WriteImage(image, filePath)) {
                Log.i(TAG, "save image success: " + filePath);
            }
            image.close();
        } catch (Exception e) {
            Log.w(TAG, "acquire camera image failed.");
            e.printStackTrace();
        }
    }

    public static boolean WriteImage(Image image, String path) {
        byte[] data = null;
        data = NV21toJPEG(YUV_420_888toNV21(image), image.getWidth(), image.getHeight());
        BufferedOutputStream bos = null;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(path));
            bos.write(data);
            bos.flush();
            bos.close();
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private static byte[] NV21toJPEG(byte[] nv21, int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        yuv.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        return out.toByteArray();
    }

    private static byte[] YUV_420_888toNV21(Image image) {
        byte[] nv21;
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        nv21 = new byte[ySize + uSize + vSize];

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        return nv21;
    }


    /**
     * 重叠检测
     *
     * @param node
     */
    private void overlapTest(Node node) {

        // 获取屏幕尺寸
        Point point = new Point();
        Display display = getWindowManager().getDefaultDisplay();
        display.getRealSize(point);
        Log.i(TAG, String.format("屏幕尺寸: x=%d, y=%d", point.x, point.y));

        Camera camera = arFragment.getArSceneView().getScene().getCamera();
        Ray ray = camera.screenPointToRay(point.x / 2f, point.y / 2f);

        new Thread(() -> {
            // 范围 20m
            for (int i = 0; i < 20; ++i) {
                int step = i;
                Vector3 curPoint = ray.getPoint(step);
                Message msg = handler.obtainMessage();
            }
        }).start();

        // 重叠检测，移除场景中重叠的节点
        Node overlapNode = arFragment.getArSceneView().getScene().overlapTest(node);
//        arFragment.getArSceneView().getScene().hitTest(ray, false);
        if (null != overlapNode) {
            arFragment.getArSceneView().getScene().removeChild(overlapNode);
        }
    }

    /**
     * 碰撞检测
     */
    private void hitTest() {
        Frame arFrame = arFragment.getArSceneView().getArFrame();
        if (null != arFrame) {
            // 获取组件中心点
            View viewById = findViewById(R.id.ar_fragment);
            Point point = new Point(viewById.getWidth() / 2, viewById.getHeight() / 2);

            List<HitResult> hitResults = arFrame.hitTest(point.x, point.y);
            for (HitResult hit : hitResults) {
                Trackable trackable = hit.getTrackable();
                if (((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
//                    placeObject(hit.createAnchor());
                    break;
                }
            }
        }
    }

    private void mockData() {
        points.clear();

        float x = 0f, y = 0f, z = 0f;
        int step = 5;

        // 向前
        for (int i = 1; i <= 5; ++i) {
            z += -step;
            points.add(new Vector3(x, y, z));
        }
        // 向左
        for (int i = 1; i <= 5; ++i) {
            x += -step;
            points.add(new Vector3(x, y, z));
        }
        // 向上
        for (int i = 1; i <= 5; ++i) {
            y += step;
            points.add(new Vector3(x, y, z));
        }
        // 向右
        for (int i = 1; i <= 10; ++i) {
            x += step;
            points.add(new Vector3(x, y, z));
        }
        // 向下
        for (int i = 1; i <= 5; ++i) {
            y += -step;
            points.add(new Vector3(x, y, z));
        }
        // 向后
        for (int i = 1; i <= 5; ++i) {
            z += step;
            points.add(new Vector3(x, y, z));
        }
    }
}
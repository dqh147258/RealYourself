package com.yxf.realyourself;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements Camera.PreviewCallback {

    private static final String TAG = "MainActivity";

    SurfaceView surfaceView;
    SurfaceView tempSurface;
    Camera camera;

    private HandlerThread thread = new HandlerThread(TAG);
    private Handler threadHandler;

    private Object bufferLock = new Object();
    byte[] buffer;
    private ExecutorService service = Executors.newCachedThreadPool();
    private ArrayBlockingQueue<Bitmap> queue = new ArrayBlockingQueue<Bitmap>(16);


    private SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            initCamera();
            Log.i(TAG, "surfaceCreated: ");
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "surfaceChanged: ");
            setCameraDisplayOrientation(MainActivity.this,
                    Camera.CameraInfo.CAMERA_FACING_FRONT, camera);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "surfaceDestroyed: ");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        thread.start();
        threadHandler = new Handler(thread.getLooper());
        surfaceView = (SurfaceView) findViewById(R.id.surface);
        tempSurface = (SurfaceView) findViewById(R.id.temp_surface);
        tempSurface.getHolder().addCallback(callback);
        surfaceView.getHolder().setKeepScreenOn(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        initCamera();
    }

    private void initCamera() {
        if (camera == null) {
            camera = Camera.open(1);
        }
        Camera.Parameters parameters = camera.getParameters();
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        for (Camera.Size size : sizes) {
            Log.v(TAG, "supported size : " + size.width + " , " + size.height);
        }
        parameters.setPreviewSize(864, 480);
        parameters.setPictureFormat(ImageFormat.JPEG);
        camera.setParameters(parameters);
        camera.setPreviewCallback(this);
        setCameraDisplayOrientation(this, Camera.CameraInfo.CAMERA_FACING_FRONT, camera);
        try {
            camera.setPreviewDisplay(tempSurface.getHolder());
        } catch (IOException e) {
            e.printStackTrace();
        }
        camera.startPreview();
    }

    public static void setCameraDisplayOrientation(Activity activity,
                                                   int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    private void updateBitmap(byte[] data) {
        synchronized (bufferLock) {
            if (buffer == null) {
                buffer = new byte[data.length];
            }
            System.arraycopy(data, 0, buffer, 0, data.length);
        }
        data = null;
        service.submit(new Runnable() {
            @Override
            public void run() {
                Camera.Size size = camera.getParameters().getPreviewSize(); //获取预览大小
                final int w = size.width;  //宽度
                final int h = size.height;
                final YuvImage image;
                byte[] tmp;
                synchronized (bufferLock) {
                    image = new YuvImage(buffer, ImageFormat.NV21, w, h, null);
                    final int len = buffer.length;
                    ByteArrayOutputStream os = new ByteArrayOutputStream(len);
                    if (!image.compressToJpeg(new Rect(0, 0, w, h), 100, os)) {
                        return;
                    }
                    tmp = os.toByteArray();
                }
                Bitmap bmp = BitmapFactory.decodeByteArray(tmp, 0, tmp.length);
                if (bmp == null) {
                    Log.e(TAG, "bitmap is null");
                    return;
                }
                Matrix matrix = new Matrix();
                matrix.postRotate(-90);
                Bitmap rotate = Bitmap.createBitmap(bmp, 0, 0, w, h, matrix, true);
                try {
                    queue.put(rotate);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                updateView();
            }
        });
    }

    private void updateView() {
        threadHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "size of queue : " + queue.size());
                long time = System.currentTimeMillis();
                Canvas canvas = surfaceView.getHolder().lockCanvas();
                if (canvas == null) {
                    return;
                }
                Bitmap bitmap = queue.poll();
                if (bitmap == null) {
                    return;
                }
                Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                Rect dest = new Rect(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
                canvas.drawBitmap(bitmap, src, dest, null);
                surfaceView.getHolder().unlockCanvasAndPost(canvas);
                Log.d(TAG, "draw time : " + (System.currentTimeMillis() - time));
            }
        });
    }


    private long previewTime = 0;

    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
        if (data == null) {
            return;
        }
        Log.d(TAG, "preview interval : " + (System.currentTimeMillis() - previewTime));
        previewTime = System.currentTimeMillis();
        updateBitmap(data);
    }
}

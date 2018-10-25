package com.shengxuan.featuredetection;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.opencv.core.CvType.CV_8UC1;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final String TAG = "MainActivity";
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("opencv_java3");
    }

    // Set the threshold for features
    private final int maxFeatures = 400;
    // Set the resolution for processing frame
    private final int captureWidth = 1920;
    private final int captureHeight = 1080;
    private TextureView cameraView;
    private TextView textView;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private String cameraId;
    private Size previewSize;
    private ImageReader previewReader;
    private final ImageReader.OnImageAvailableListener onImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            image = previewReader.acquireLatestImage();
            if (image == null) {
                return;
            } else {
                // If the threshold is reached and cue is not visible, then show the cue
                if (isExceedThreshold(maxFeatures, convertYUV2Mat(image).getNativeObjAddr())) {
                    if (textView.getVisibility() == View.INVISIBLE) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                } else {
                    if (textView.getVisibility() == View.VISIBLE) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textView.setVisibility(View.INVISIBLE);
                            }
                        });
                    }
                }
            }
            image.close();
        }

    };
    private int width;
    private int height;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession.CaptureCallback sessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
        }
    };
    private CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cd) {
            cameraDevice = cd;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    public MainActivity() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    2);
        }
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraView = findViewById(R.id.camera_preview);
        textView = findViewById(R.id.visual_cue);
        cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int _width, int _height) {
                Log.e(TAG, "default width:" + _width + "default height " + _height);
                width = _width;
                height = _height;
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int _width, int _height) {
                configureTransform(_width, _height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        });
    }

    private void getCameraId() {
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                // Only use back camera in this app
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                this.cameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        // Check permission for camera
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        try {
            cameraManager.openCamera(cameraId, cameraDeviceStateCallback, null);
            startBackgroundThread();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * transform texture view when the phone is rotated
     *
     * @param viewWidth  view's width
     * @param viewHeight view's height
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == cameraView) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF previewRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            previewRect.offset(centerX - previewRect.centerX(), centerY - previewRect.centerY());
            matrix.setRectToRect(viewRect, previewRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        cameraView.setTransform(matrix);
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Select smallest suitable size from the supported size list
     *
     * @param sizes  device support size list
     * @param width  view's width
     * @param height view's height
     */
    private Size getPreferredPreviewSize(Size[] sizes, int width, int height) {
        List<Size> collectorSizes = new ArrayList<>();
        for (Size option : sizes) {
            if (width > height) {
                // Make sure the ratio is same as display ratio
                if (option.getWidth() >= width && option.getHeight() >= height && option.getWidth() / (float) option.getHeight() == width / (float) height) {
                    collectorSizes.add(option);
                }
            } else {
                if (option.getHeight() >= width && option.getWidth() >= height && option.getWidth() / (float) option.getHeight() == height / (float) width) {
                    collectorSizes.add(option);
                }
            }
        }
        // Choose the smallest size
        if (collectorSizes.size() > 0) {
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                // Compare the sizes by area
                public int compare(Size s1, Size s2) {
                    return Long.signum(s1.getWidth() * s1.getHeight() - s2.getWidth() * s2.getHeight());
                }
            });
        }
        return sizes[0];
    }

    private void createCameraPreview() {
        try {
            // Get texture instance
            SurfaceTexture texture = cameraView.getSurfaceTexture();
            // Set dimension of texture
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            // New surface for preview
            Surface surface = new Surface(texture);
            // Get preview request builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // set the image reader for image processing
            previewReader = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.addTarget(previewReader.getSurface());
            cameraDevice.createCaptureSession(Arrays.asList(surface, previewReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    // if camera is closed, return
                    if (null == cameraDevice) {
                        return;
                    }
                    cameraSession = cameraCaptureSession;
                    // Set control mode
                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    try {
                        // Repeating request to enable preview
                        cameraSession.setRepeatingRequest(captureRequestBuilder.build(), sessionCaptureCallback, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Set camera output for preview
     * 1. Get the current camera
     * 2. Check if there is a need to rotate display
     * 3. Find the preferred display size out of output sizes
     *
     * @param width  previewWidth
     * @param height previewHeight
     */
    private void setUpCameraOutputs(int width, int height) {
        // Choose the back camera
        getCameraId();
        CameraCharacteristics characteristics = null;
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // Get device rotation
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        // Get sensor direction, normally it is set as 90 by phone producers
        int sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean swappedDimensions = false;
        Log.d(TAG, "displayRotation: " + displayRotation);
        Log.d(TAG, "sensorOritentation: " + sensorOrientation);
        switch (displayRotation) {
            // ROTATION_0 and ROTATION_180 are vertical
            // if display is vertical but sensor is 90 or 270, swap the dimensions
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    Log.d(TAG, "swappedDimensions set true !");
                    swappedDimensions = true;
                }
                break;
            // ROTATION_90 and ROTATION_270 are horizontal
            // if display is horizontal but sensor is 0 or 180, swap the dimensions
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true;
                }
                break;
            default:
                Log.e(TAG, "Display rotation is invalid: " + displayRotation);
        }

        // Get the current display size
        Point displaySize = new Point();
        getWindowManager().getDefaultDisplay().getSize(displaySize);
        int rotatedPreviewWidth = width;
        int rotatedPreviewHeight = height;

        // if rotation is needed, swap the dimensions
        if (swappedDimensions) {
            rotatedPreviewWidth = height;
            rotatedPreviewHeight = width;
        }

        // Get the best output size
        previewSize = getPreferredPreviewSize(map.getOutputSizes(SurfaceTexture.class),
                rotatedPreviewWidth, rotatedPreviewHeight);
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (cameraView.isAvailable()) {
            setUpCameraOutputs(cameraView.getWidth(), cameraView.getHeight());
            configureTransform(cameraView.getWidth(), cameraView.getHeight());
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraSession != null) {
            cameraSession.close();
            cameraSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        stopBackgroundThread();
    }

    /**
     * Function to convert YUV_420_888 format image to Mat format in opencv
     */
    private Mat convertYUV2Mat(Image image) {
        byte[] data;
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        data = new byte[ySize + uSize + vSize];

        //U and V are swapped
        yBuffer.get(data, 0, ySize);
        vBuffer.get(data, ySize, vSize);
        uBuffer.get(data, ySize + vSize, uSize);

        Mat mat = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CV_8UC1);
        mat.put(0, 0, data);
        Mat mRGB = new Mat();
        Imgproc.cvtColor(mat, mRGB, Imgproc.COLOR_YUV2BGR_NV21, 3);
        return mRGB;
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     * Using opencv in c++ to detect if no. of features in the image > threshold
     */
    public native boolean isExceedThreshold(int threshold, long mAddress);

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            activity.requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (activity != null) {
                                        activity.finish();
                                    }
                                }
                            })
                    .create();
        }

    }
}

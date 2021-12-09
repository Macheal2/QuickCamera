package com.meig.quickcamera;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.SyncStateContract;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 添加相机相关权限
 * 通过Camera.open(int)获得一个相机实例
 * 利用camera.getParameters()得到相机实例的默认设置Camera.Parameters
 * 如果需要的话，修改Camera.Parameters并调用camera.setParameters(Camera.Parameters)来修改相机设置
 * 调用camera.setDisplayOrientation(int)来设置正确的预览方向
 * 调用camera.setPreviewDisplay(SurfaceHolder)来设置预览，如果没有这一步，相机是无法开始预览的
 * 调用camera.startPreview()来开启预览，对于拍照，这一步是必须的
 * 在需要的时候调用camera.takePicture(Camera.ShutterCallback, Camera.PictureCallback, Camera.PictureCallback, Camera.PictureCallback)来拍摄照片
 * 拍摄照片后，相机会停止预览，如果需要再次拍摄多张照片，需要再次调用camera.startPreview()来重新开始预览
 * 调用camera.stopPreview()来停止预览
 * 一定要在onPause()的时候调用camera.release()来释放camera，在onResume中重新开始camera
 */
public class MainActivity extends AppCompatActivity {
    public static final String TAG = "MainActivity";
    public static final int UPDATE_FRAME_SAVED = 1000;
    public static final int CAMERA_VIEW_INIT = 1001;
    public static final int CAMERA_VIEW_PREVIEW = 1002;
    public static final int UPDATE_FRAME_ROUND = 1003;
    public static final long NEED_SIZE = 500_000_000;//500M

    private SurfaceView mSurfaceView;
    private Button mPicture;
    private ImageView mImage;
    private TextView mFrameTips;
    private TextView mFrameSaved;
    private TextView mFrameRound;

    private Camera mCamera;
    private SurfaceHolder mHolder;

    private PowerManager mPowerManger;
    private PowerManager.WakeLock mWakeLock;

    private int mDeiveceWith = 0;   //保存设备屏幕宽

    private static float DEFAUT_RATIO = 1.33f;  //预览图宽高比
    private int mWidth = 0; //预览图宽
    private int mHeight = 0;    //预览图高

    private int mRealCount = 0;//预览图片帧

    private static final int MAX_SAVED_COUNT = 500; ////最大保存图片数量
    private static final int MAX_FRAME_COUNT = Integer.MAX_VALUE - 10;
    private int mCurrentCount = 0; //当前保存的图片
    private int mRoundCount = 0;

    private int mLastSavedPicId = -1;//记录当前正在保存的图片

    //创建线程池，执行保存图片操作。
    private ExecutorService mSingleThreadPool = Executors.newSingleThreadExecutor();

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case UPDATE_FRAME_SAVED:
                    if (mFrameSaved != null) {
                        mFrameSaved.setText("Current saved: " + msg.obj);
                    }
                    break;
                case CAMERA_VIEW_INIT:
                    initCameraView();
                    break;
                case CAMERA_VIEW_PREVIEW:
                    if (mCamera == null) {
                        mCamera = getCamera();
                    }
                    if (mHolder != null && mCamera != null) {
                        startPreview(mCamera, mHolder);
                    }
                    break;
                case UPDATE_FRAME_ROUND:
                    if (mFrameRound != null) {
                        mFrameRound.setText("Frame round: " + msg.obj);
                    }
                    break;
            }
        }
    };

    private Camera.PreviewCallback mCameraCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            //Log.d(TAG, "onPreviewFrame: data size:" + data.length);
            //Bitmap bm = getOriBitmap(data, mWidth, mHeight);
            if (mWidth > 0 && mHeight > 0) {
                byte[] yuv = rotateYUV(data, mWidth, mHeight);
                Bitmap bm = getBitmap(yuv, mHeight, mWidth);
                mImage.setImageBitmap(bm);
                mFrameTips.setText("Current frame: " + mRealCount);

                mRealCount++;
                if (mRealCount > MAX_FRAME_COUNT) {
                    mRealCount = 0;
                }
            }
        }
    };

    private SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i("Yar", " surfaceCreated()");
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i("Yar", " surfaceChanged() width = " + width + ", height = " + height);
            if (mCamera != null) {
                //mCamera.stopPreview(); //no need to stopPreview
                startPreview(mCamera, holder);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i("Yar", " surfaceDestroyed()");
            releaseCamera();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDeiveceWith = getWindowManager().getDefaultDisplay().getWidth();
        mPowerManger = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManger.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "QuickCamera");

        setContentView(R.layout.activity_main);
        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mPicture = (Button) findViewById(R.id.btn_picture);
        mImage = (ImageView) findViewById(R.id.image);
        mFrameTips = (TextView) findViewById(R.id.tv_frame);
        mFrameSaved = (TextView) findViewById(R.id.save_frame);
        mFrameRound = (TextView) findViewById(R.id.round_count);

        android.view.ViewGroup.LayoutParams lp = mSurfaceView.getLayoutParams();
        if (mDeiveceWith > 0) {
            lp.width = mDeiveceWith;
            lp.height = mDeiveceWith / 3 * 4;
        } else {
            lp.width = 540; // 自定义 宽:高 = 3:4
            lp.height = 720;
        }
        mSurfaceView.setLayoutParams(lp);

        mHolder = mSurfaceView.getHolder();
        mHandler.sendEmptyMessage(CAMERA_VIEW_INIT);
        //拍照
        /*mPicture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (this) {
                    if (mCamera != null) {
                        mCamera.takePicture(null, null, new Camera.PictureCallback() {
                            @Override
                            public void onPictureTaken(byte[] data, Camera camera) {
                                savePic(data, "Image.png");
                                //重新开始预览
                                startPreview(mCamera, mHolder);
                            }
                        });
                    }
                }
            }
        });*/
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        long size = getAvailableSize();
        Log.i("Yar1", "0. onResume: size = " + size);
        if (size < NEED_SIZE) { //To ensure have enough storage to take pictures
            finish();
        } else if (mCamera == null) {
            mHandler.sendEmptyMessage(CAMERA_VIEW_PREVIEW);
            Log.i("Yar1", "1. onResume: ");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        releaseCamera();
    }

    private void initCameraView() {
        if (mCamera == null) {
            mCamera = getCamera();
        }
        mHolder.addCallback(mSurfaceCallback);
    }

    /**
     * 获取摄像头
     *
     * @return
     */
    private Camera getCamera() {
        if (Camera.getNumberOfCameras() > 0) {
            Camera mCamera = null;
            try {
                mCamera = Camera.open(0);
                Camera.Parameters parameters = mCamera.getParameters();
                //设置图片格式
                parameters.setPictureFormat(ImageFormat.JPEG);
                //设置图片方向
                parameters.setRotation(90);
                //设置预览大小
                List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
                for (int i = 0; i < sizes.size(); i++) {
                    int tmpW = sizes.get(i).width;
                    int tmpH = sizes.get(i).height;
                    float isFull = tmpW * 100 / tmpH / (float)100;//只保留两位小数
                    if (isFull == DEFAUT_RATIO && (tmpW > mWidth || tmpH > mHeight)) {
                        mWidth = tmpW;
                        mHeight = tmpH;
                        android.util.Log.i("Yar", " isFull = " + isFull + ", mWidth = "  + mWidth + ", mHeight = " + mHeight);
                    }
                }
                if (mWidth > 0 && mHeight > 0) {
                    parameters.setPreviewSize(mWidth, mHeight);
                } else {
                    Camera.Size curSize = mCamera.getParameters().getPreviewSize();
                    mWidth = curSize.width;
                    mHeight = curSize.height;
                }
                mCamera.setParameters(parameters);
                mCamera.setDisplayOrientation(90);
            } catch (Exception e) {
                mCamera = null;
            } finally {
                return mCamera;
            }
        }
        return null;
    }

    /**
     * 开启预览
     *
     * @param camera
     * @param holder
     */
    private void startPreview(Camera camera, SurfaceHolder holder) {
        try {
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(mCameraCallback);
            camera.startPreview();
        } catch (IOException e) {
            Log.e("Yar", "startPreview() e = " + e);
        }
    }

    /**
     * 释放摄像头资源
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);/*这个是预览的回调,里面会返回一个Byte[]和相应的Camera*/
            mCamera.stopPreview();/*取消预览功能*/
            mCamera.release();
            mCamera = null;
        }
    }

    //未用到该方法
    private Bitmap getOriBitmap(byte[] data, int imageWidth, int imageHeight) {
        BitmapFactory.Options newOpts = new BitmapFactory.Options();
        newOpts.inJustDecodeBounds = true;
        YuvImage yuvimage = new YuvImage(
                data,
                ImageFormat.NV21,
                imageWidth,
                imageHeight,
                null);
        ByteArrayOutputStream pic = new ByteArrayOutputStream();
        android.util.Log.i("Yar", " imageWidth = " + imageWidth + ", imageHeight = " + imageHeight);
        yuvimage.compressToJpeg(new Rect(0, 0, imageWidth, imageHeight), 100, pic);// 80--JPG图片的质量[0-100],100最高
        byte[] rawImage = pic.toByteArray();
        savePic(rawImage, "pic_40.jpg");
        //将rawImage转换成bitmap
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length, options);

        /*Matrix matrix = new Matrix();
        matrix.setRotate(90);
        Bitmap bmp = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight, matrix, false);*/

        //保存的图片还是未旋转的图片
        //bmp.compress(Bitmap.CompressFormat.JPEG, 100, pic);
        //savePic(pic.toByteArray(), "pic_41.jpg");
        return bitmap;
    }

    private Bitmap getBitmap(byte[] yuv, int imageWidth, int imageHeight) {
        YuvImage yuvImage = new YuvImage(yuv, ImageFormat.NV21, imageWidth, imageHeight, null);
        //android.util.Log.i("Yar", "2. =====yuv end()=====");
        ByteArrayOutputStream pic = new ByteArrayOutputStream();
        //android.util.Log.i("Yar", " imageWidth = " + imageWidth + ", imageHeight = " + imageHeight);
        yuvImage.compressToJpeg(new Rect(0, 0, imageWidth, imageHeight), 100, pic);// 80--JPG图片的质量[0-100],100最高
        //android.util.Log.i("Yar", "3. =====pic data end()=====");
        byte[] rawImage = pic.toByteArray();

        final int pic_id = mCurrentCount;

        mSingleThreadPool.execute(new Runnable() {//通过线程池保存图片，不影响主线程
            @Override
            public void run() {

                //android.util.Log.i("Yar", " mLastSavedPicId = " + mLastSavedPicId + ", pic_id = " + pic_id);
                if (pic_id < MAX_SAVED_COUNT && mLastSavedPicId != pic_id) {
                    savePic(rawImage, "pic_" + pic_id + ".jpg");
                    mLastSavedPicId = pic_id;
                    mCurrentCount++;
                    mHandler.sendMessage(mHandler.obtainMessage(UPDATE_FRAME_SAVED, pic_id));
                } else if (mCurrentCount >= MAX_SAVED_COUNT) {
                    mCurrentCount = 0;
                    mRoundCount++;
                    //android.util.Log.i("Yar", " mRoundCount = " + mRoundCount + ", mCurrentCount = " + mCurrentCount);
                    mHandler.sendMessage(mHandler.obtainMessage(UPDATE_FRAME_ROUND, mRoundCount));
                }
            }
        });
        //android.util.Log.i("Yar", "4. =====save data end()=====");
        //将rawImage转换成bitmap
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length, options);
        //android.util.Log.i("Yar", "5. =====bitmap data end()=====");
        return bitmap;
    }

    private byte[] rotateYUV(byte[] data, int imageWidth, int imageHeight) {
        //android.util.Log.i("Yar", "0. =====rotate start()===== mCount = " + mCount);
        Camera.Size size;

        byte [] yuv = new byte[imageWidth*imageHeight*3/2];
        // Rotate the Y luma
        int i = 0;
        for(int x = 0; x < imageWidth; x++) {
            for(int y = imageHeight-1;y >= 0;y--) {
                yuv[i] = data[y*imageWidth+x];
                i++;
            }
        }
        // Rotate the U and V color components
        i = imageWidth*imageHeight*3/2-1;
        for (int x = imageWidth-1; x > 0; x=x-2) {
            for (int y = 0; y < imageHeight/2; y++) {
                yuv[i] = data[(imageWidth*imageHeight) + (y*imageWidth)+x];
                i--;
                yuv[i] = data[(imageWidth*imageHeight) + (y*imageWidth)+(x-1)];
                i--;
            }
        }
        //android.util.Log.i("Yar", "1. =====rotate end()=====");
        return yuv;
    }

    private synchronized void savePic(byte[] data, String name) {
        //ContextWrapper cw = new ContextWrapper(getApplicationContext());
        //File directory = cw.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File tempFile = new File(directory, name);

        if (tempFile.exists()) {//删除已存在的图片
            tempFile.delete();
        }
        //Log.d("Yar", "onPictureTaken: 接收到拍照后的数据, tempFile = " + tempFile.getAbsolutePath());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tempFile);
            fos.write(data);
        } catch (FileNotFoundException e) {
            Log.d("Yar", "0. FileNotFoundException = " + e);
        } catch (IOException e) {
            Log.d("Yar", "1. IOException = " + e);
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.d("Yar", "2. IOException = " + e);
                }
                // 扫描本地mp4文件并添加到本地视频库
                MediaScannerConnection mMediaScanner = new MediaScannerConnection(this, null);
                mMediaScanner.connect();
                if (mMediaScanner !=null && mMediaScanner.isConnected()) {
                    mMediaScanner.scanFile(tempFile.getAbsolutePath(), ".jpg");
                }
            }
        }
    }

    public long getAvailableSize() {
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) ;
        StatFs stat = new StatFs(path.getPath());
        long availableBytes = 0;
        if (android.os.Build.VERSION.SDK_INT >= 18) {
            availableBytes = stat.getAvailableBytes();
        } else {
            long blockSize = stat.getBlockSize();
            long totalBlocks = stat.getBlockCount();
            availableBytes =  totalBlocks * blockSize;
        }
        android.util.Log.i("Yar1", " path = " + path.getPath() + " availableBytes = " + availableBytes);
        return availableBytes;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 100:
                if (grantResults.length > 0) {
                    for (int i = 0; i < grantResults.length; i++) {
                        Log.i("Yar","onRequestPermissionsResult granted i = " + i);
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                            finish();
                        }
                    }
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    //finish();
                } else {
                    finish();
                }
                break;
        }
    }
}

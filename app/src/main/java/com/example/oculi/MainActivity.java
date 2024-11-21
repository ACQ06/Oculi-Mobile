package com.example.oculi;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.speech.tts.TextToSpeech;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int CAMERA_REQUEST_CODE = 100;
    private static final int REQUEST_AUDIO_PERMISSION_CODE = 200;
    private final OkHttpClient client = new OkHttpClient();
    private ImageCapture imageCapture;
    private TextToSpeech textToSpeech;
    private boolean isScanning = false;
    private Button scanButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        init();
    }

    private void init(){
        requestPermissions();
        textToSpeech = new TextToSpeech(this, this);

        scanButton = findViewById(R.id.scanButton);

        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                // If currently scanning, stop the process and clear the TTS queue
                stopScanning();
            } else {
                // If not scanning, start scanning
                startScanning();
            }
        });
    }

    private void requestPermissions(){
        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
        } else {
            startCamera();
        }

//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION_CODE);
//        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        }

//        if (requestCode == REQUEST_AUDIO_PERMISSION_CODE) {
//            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//            } else {
//                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show();
//            }
//        }
    }

    private void startScanning() {
        speak("Scanning");
        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
        isScanning = true;
        scanButton.setText("Stop");  // Change button text to "Stop"

        // Capture photo and process
        capturePhotoAsBase64().thenAccept(base64Image -> {
            if (base64Image != null) {
                JSONObject jsonBody = new JSONObject();
                try {
                    jsonBody.put("image", base64Image);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                makePostRequest(this.getString(R.string.server_address) + "/api/model/extract-text", jsonBody.toString());
            } else {
                speak("Failed to capture photo.");
                Toast.makeText(this, "Failed to capture photo.", Toast.LENGTH_SHORT).show();
                Log.e("CameraXApp", "Failed to capture photo.");
            }
        });
    }

    private void stopScanning() {
        // Stop any ongoing text-to-speech queues
        if (textToSpeech != null) {
            textToSpeech.stop();
        }

        // Change the button back to "Scan"
        scanButton.setText("Scan");

        // Reset scanning state
        isScanning = false;
        Toast.makeText(this, "Scanning stopped", Toast.LENGTH_SHORT).show();
    }

    public CompletableFuture<String> capturePhotoAsBase64() {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        if (imageCapture == null) {
            Log.e("CameraXApp", "ImageCapture use case is not initialized.");
            resultFuture.complete(null);
            return resultFuture;
        }

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = convertImageProxyToBitmap(imageProxy);

                if (bitmap != null) {
                    String encodedImage = encodeBitmapToBase64(bitmap);
                    resultFuture.complete(encodedImage);
                } else {
                    resultFuture.complete(null);
                }

                imageProxy.close();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e("CameraXApp", "Photo capture failed: " + exception.getMessage(), exception);
                resultFuture.complete(null);
            }
        });

        return resultFuture;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Build the Preview use case
                Preview preview = new Preview.Builder().build();

                // Build the ImageCapture use case with flashlight (torch mode)
                imageCapture = new ImageCapture.Builder()
                        .setFlashMode(ImageCapture.FLASH_MODE_ON) // Enable the flashlight in torch mode
                        .build();

                // Set up camera selector to use the back camera
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Set the surface provider for the preview view
                preview.setSurfaceProvider(((androidx.camera.view.PreviewView) findViewById(R.id.view_cameraPreview)).getSurfaceProvider());

                // Bind the use cases to the camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraXApp", "Camera initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // POST Request
    public void makePostRequest(String url, String json) {
        speak("Please wait...");
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // Loop through each item in the JSONArray
                runOnUiThread(() -> {
                    speak("Request Error");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        // Parse the JSON response
                        assert response.body() != null;
                        String responseBody;
                        responseBody = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseBody);

                        // Get the message from the JSON response
                        JSONArray message = jsonResponse.getJSONArray("message");

                        // Loop through each item in the JSONArray
                        runOnUiThread(() -> {

                            for (int i = 0; i < message.length(); i++) {
                                try {
                                    // Extract each message
                                    String individualMessage = message.getString(i);

                                    // Log the individual message
                                    Log.d("Extracted Text", individualMessage);

                                    // Speak the message (if necessary)
                                    speak(individualMessage);

                                    // Display the message in a Toast
                                    Toast.makeText(MainActivity.this, individualMessage, Toast.LENGTH_SHORT).show();
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Request failed", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    // Function to speak the text passed to it
    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, null);
        }
    }

    private Bitmap convertImageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        // Decode byte array to Bitmap
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        // Resize the bitmap to 1920x1080 (or smaller to maintain aspect ratio)
        return resizeBitmap(bitmap, 1080, 1920);
    }

    private Bitmap resizeBitmap(Bitmap original, int maxWidth, int maxHeight) {
        // Get original dimensions
        int width = original.getWidth();
        int height = original.getHeight();

        // Calculate the scaling factor
        float scaleFactor = Math.min((float) maxWidth / width, (float) maxHeight / height);

        // Scale bitmap to fit within the specified bounds
        int newWidth = Math.round(width * scaleFactor);
        int newHeight = Math.round(height * scaleFactor);

        // Create the resized bitmap
        return Bitmap.createScaledBitmap(original, newWidth, newHeight, false);
    }


    private String encodeBitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // TextToSpeech engine is initialized successfully
            speak("Hello, ready for scan");
        } else {
            // Initialization failed
            Log.e("TTS", "Initialization failed.");
        }
    }
}

package ar.com.biopus.abismocam;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends ComponentActivity {
    private static final int BG = 0xff090909, PANEL = 0xff232323, ACCENT = 0xffffffff, TEXT = 0xffffffff, MUTED = 0xffbdbdbd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService oscExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    private final Osc.Hold hold = new Osc.Hold(this::queueOsc);
    private final AtomicReference<CaptureRequest> pendingCapture = new AtomicReference<>();
    private final FrameScaler frameScaler = new FrameScaler();
    private volatile Thread analysisThread;
    private volatile long lastAnalysisMs, lastNativeStartMs, lastNativeEndMs;
    private final CaptureSequence sequence = new CaptureSequence(new CaptureSequence.Listener() {
        @Override public void onOsc(int value) {
            if (value == 1) hold.press(config.targets(), config.address);
            else hold.release();
        }
        @Override public void onCount(int seconds) {
            if (countdown == null) return;
            countdown.setText(seconds > 0 ? Integer.toString(seconds) : "");
            countdown.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
            if (seconds > 0) {
                countdown.setScaleX(1.12f); countdown.setScaleY(1.12f);
                countdown.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                captureStatus.setText("Prepará tu foto");
            }
        }
        @Override public void onCapture() {
            android.util.Log.d("AbismoCapture", "request " + captureGeneration);
            captureStatus.setText("Capturando…");
            pendingCapture.set(new CaptureRequest(captureGeneration, config.frontCamera));
        }
        @Override public void onLive() { restoreLiveView(); }
        @Override public void onTimeout() {
            android.util.Log.w("AbismoCapture", "timeout " + captureGeneration);
            android.util.Log.w("AbismoCapture", "analysis=" + lastAnalysisMs + " native=" + lastNativeStartMs + "/" + lastNativeEndMs
                + " now=" + SystemClock.elapsedRealtime() + " stack=" + (analysisThread == null ? "none" : java.util.Arrays.toString(analysisThread.getStackTrace())));
            captureGeneration++;
            pendingCapture.set(null);
            toast("No llegó un cuadro de cámara. Volvé a intentarlo.");
        }
    });
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            sequence.tick(SystemClock.elapsedRealtime());
            if (sequence.isBusy()) main.postDelayed(this, 20);
        }
    };
    private Config config;
    private NdiSender ndi;
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private TextView cameraStatus, oscStatus, ndiStatus, countdown, captureStatus;
    private ShutterButton trigger;
    private Button streamButton;
    private TextView ndiIndicator;
    private FrameLayout cameraScreen;
    private LinearLayout settingsPanel;
    private boolean ndiWanted = true;
    private final Runnable openSettings = () -> { if (this.resumed && !sequence.isBusy()) showSettings(); };
    private ImageButton settingsButton;
    private ImageView frozenFrame, lastPhoto;
    private View flash;
    private Uri lastPhotoUri;
    private Bitmap lastThumbnail;
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean streaming;
    private boolean starting, resumed, settingsVisible, cameraReady;
    private int cameraGeneration, captureGeneration, ndiGeneration;
    volatile long ndiFramesSent;
    private long lastSentNs, statsStartMs;
    private int frameCount;
    private String oscMessage = "Configurá los destinos para enviar OSC.";
    private String ndiMessage = "NDI detenido";
    private static final class CaptureRequest {
        final int generation;
        final boolean mirrored;
        CaptureRequest(int generation, boolean mirrored) { this.generation = generation; this.mirrored = mirrored; }
    }
    private final ActivityResultLauncher<String> storagePermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted && resumed && !settingsVisible) beginCountdown();
            else if (!granted) toast("Hace falta permiso para guardar la foto en este Android.");
        });
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted && !settingsVisible) bindCamera();
            else if (!granted && cameraStatus != null) cameraStatus.setText("Cámara sin permiso · tocá aquí para habilitarla");
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        config = Config.load(this);
        config.save(this);
        // El SDK requiere mantener una instancia de NsdManager antes de crear el emisor.
        nsdManager = (NsdManager)getSystemService(NSD_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (settingsVisible) showCamera();
                else { cancelCapture(); stopNdi(); finish(); }
            }
        });
        showCamera();
    }

    private LinearLayout root() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
            return insets;
        });
        settingsPanel = root;
        cameraScreen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void showCamera() {
        if (settingsPanel != null) {
            cameraScreen.removeView(settingsPanel);
            settingsPanel = null;
            streamButton = null;
            settingsVisible = false;
            return;
        }
        settingsVisible = false;
        FrameLayout screen = new FrameLayout(this);
        cameraScreen = screen;
        screen.setBackgroundColor(Color.BLACK);
        setContentView(screen);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        screen.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        frozenFrame = new ImageView(this);
        frozenFrame.setId(R.id.frozen_frame);
        frozenFrame.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frozenFrame.setVisibility(View.GONE);
        screen.addView(frozenFrame, new FrameLayout.LayoutParams(-1, -1));

        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xaa000000, Color.TRANSPARENT}));
        screen.addView(topShade, new FrameLayout.LayoutParams(-1, dp(180), Gravity.TOP));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xee000000, Color.TRANSPARENT}));
        screen.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(320), Gravity.BOTTOM));

        FrameLayout controls = new FrameLayout(this);
        screen.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        ViewCompat.setOnApplyWindowInsetsListener(controls, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(18) + bars.left, dp(6) + bars.top, dp(18) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        LinearLayout header = row();
        ndiIndicator = label("○ NDI", 12, MUTED);
        ndiIndicator.setId(R.id.ndi_indicator);
        ndiIndicator.setGravity(Gravity.CENTER);
        header.addView(ndiIndicator, new LinearLayout.LayoutParams(dp(94), dp(48)));
        TextView title = label("EL ABISMO", 12, TEXT);
        title.setLetterSpacing(0.2f); title.setGravity(Gravity.CENTER); title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        settingsButton = new ImageButton(this);
        settingsButton.setId(R.id.settings);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setContentDescription("Ajustes: mantener pulsado 3 segundos");
        settingsButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        settingsButton.setBackground(background(0x33000000, 24));
        settingsButton.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && v.isEnabled()) {
                main.removeCallbacks(openSettings);
                main.postDelayed(openSettings, 3000);
                v.setPressed(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                || (event.getActionMasked() == MotionEvent.ACTION_MOVE && (event.getX() < 0 || event.getY() < 0 || event.getX() > v.getWidth() || event.getY() > v.getHeight()))) {
                main.removeCallbacks(openSettings); v.setPressed(false);
            }
            return true;
        });
        FrameLayout settingsSlot = new FrameLayout(this);
        settingsSlot.addView(settingsButton, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END));
        header.addView(settingsSlot, new LinearLayout.LayoutParams(dp(94), dp(48)));
        controls.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        countdown = label("", 136, Color.WHITE);
        countdown.setId(R.id.countdown);
        countdown.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        countdown.setGravity(Gravity.CENTER);
        countdown.setShadowLayer(dp(16), 0, dp(2), 0x77000000);
        countdown.setVisibility(View.GONE);
        countdown.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(countdown, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout footer = column();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        cameraStatus = label("Iniciando cámara…", 10, MUTED);
        cameraStatus.setGravity(Gravity.CENTER);
        cameraStatus.setShadowLayer(dp(3), 0, 1, Color.BLACK);
        cameraStatus.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } else if (!cameraReady) bindCamera();
        });
        captureStatus = label("Soltá el botón para iniciar · 5 s", 13, TEXT);
        captureStatus.setId(R.id.capture_status);
        captureStatus.setGravity(Gravity.CENTER);
        captureStatus.setPadding(0, dp(12), 0, dp(6));
        footer.addView(captureStatus);
        FrameLayout shutterRow = new FrameLayout(this);
        trigger = new ShutterButton(this);
        trigger.setId(R.id.shutter);
        trigger.setOnClickListener(v -> beginCountdown());
        shutterRow.addView(trigger, new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER));
        lastPhoto = new ImageView(this);
        lastPhoto.setContentDescription("Abrir última foto guardada");
        lastPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastPhoto.setBackground(background(0x55ffffff, 10)); lastPhoto.setClipToOutline(true);
        if (lastThumbnail != null) lastPhoto.setImageBitmap(lastThumbnail);
        else lastPhoto.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams thumbLayout = new FrameLayout.LayoutParams(dp(46), dp(56), Gravity.START | Gravity.CENTER_VERTICAL);
        thumbLayout.leftMargin = dp(22);
        shutterRow.addView(lastPhoto, thumbLayout);
        lastPhoto.setOnClickListener(v -> {
            if (lastPhotoUri == null || sequence.isBusy()) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastPhotoUri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
            catch (android.content.ActivityNotFoundException unavailable) { toast("Foto guardada en Pictures/AbismoCam."); }
        });
        TextView timerLabel = label("5 s", 14, TEXT); timerLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams timerLayout = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        timerLayout.rightMargin = dp(22); shutterRow.addView(timerLabel, timerLayout);
        footer.addView(shutterRow, new LinearLayout.LayoutParams(-1, dp(106)));
        TextView mode = label("FOTO", 11, TEXT); mode.setLetterSpacing(0.2f); mode.setTypeface(null, Typeface.BOLD);
        footer.addView(mode);
        cameraStatus.setPadding(0, dp(12), 0, 0);
        footer.addView(cameraStatus);
        ndiStatus = label(ndiMessage, 10, MUTED);
        ndiStatus.setGravity(Gravity.CENTER);
        footer.addView(ndiStatus);
        oscStatus = label(targetSummary(), 10, MUTED);
        oscStatus.setGravity(Gravity.CENTER); oscStatus.setMaxLines(2);
        footer.addView(oscStatus);
        TextView ndiLink = label("NDI® · ndi.video", 10, MUTED);
        ndiLink.setGravity(Gravity.CENTER); ndiLink.setPadding(0, dp(4), 0, 0);
        footer.addView(ndiLink);
        controls.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        flash = new View(this); flash.setId(R.id.screen_flash); flash.setBackgroundColor(Color.WHITE); flash.setVisibility(View.GONE);
        screen.addView(flash, new FrameLayout.LayoutParams(-1, -1));
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void bindCamera() {
        if (settingsVisible || isDestroyed()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cameraReady = false;
        final int generation = ++cameraGeneration;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (generation != cameraGeneration || settingsVisible || isDestroyed()) return;
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(new ResolutionStrategy(new Size(config.width, config.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                Preview preview = new Preview.Builder().setResolutionSelector(resolution)
                    .setTargetFrameRate(new Range<>(config.fps, config.fps)).setTargetRotation(Surface.ROTATION_0).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(cameraExecutor, this::analyze);
                CameraSelector selector = config.frontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);
                cameraReady = true;
                cameraStatus.setText((config.frontCamera ? "Frontal" : "Trasera") + " · vista previa · sin audio");
                if (resumed && ndiWanted && !streaming && !starting) startNdi();
            } catch (Exception failure) {
                cameraStatus.setText("No se pudo abrir la cámara. Tocá para reintentar.");
                toast("Cámara: " + failure.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        analysisThread = Thread.currentThread(); lastAnalysisMs = SystemClock.elapsedRealtime();
        try {
            // Copiar un único cuadro antes de liberarlo. El bitmap local nunca se
            // escribe en el buffer original que consume NDI.
            CaptureRequest request = pendingCapture.getAndSet(null);
            long now = System.nanoTime();
            long interval = (long)(1_000_000_000.0 / config.fps * 0.9);
            boolean send = streaming && ndi != null && now - lastSentNs >= interval;
            if (!send && request == null) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4) throw new IllegalStateException("La cámara no produjo RGBA.");
            int outWidth = config.height, outHeight = config.width;
            ByteBuffer frame = plane.getBuffer();
            int stride = plane.getRowStride();
            boolean scaled = image.getWidth() != outWidth || image.getHeight() != outHeight;
            if (scaled) {
                frame = frameScaler.scale(frame, image.getWidth(), image.getHeight(), stride, outWidth, outHeight);
                stride = outWidth * 4;
            }
            if (request != null) {
                android.util.Log.d("AbismoCapture", "copy " + request.generation);
                try {
                    // CameraX puede avanzar el cursor al copiar; NDI necesita
                    // recibir el buffer original desde su posición inicial.
                    ByteBuffer pixels = image.getPlanes()[0].getBuffer();
                    int position = pixels.position(), limit = pixels.limit();
                    Bitmap original;
                    try {
                        if (scaled) {
                            original = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
                            original.copyPixelsFromBuffer(frame.duplicate());
                        } else original = image.toBitmap();
                    }
                    finally { pixels.limit(limit); pixels.position(position); }
                    photoExecutor.execute(() -> {
                        try {
                            Bitmap photo = original;
                            if (request.mirrored) {
                                Matrix mirror = new Matrix(); mirror.setScale(-1f, 1f);
                                photo = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), mirror, false);
                            }
                            Bitmap ready = photo;
                            main.post(() -> acceptSnapshot(request.generation, ready));
                        } catch (RuntimeException failure) {
                            main.post(() -> captureFailed(request.generation, failure));
                        }
                    });
                } catch (RuntimeException failure) {
                    main.post(() -> captureFailed(request.generation, failure));
                }
            }
            if (!send) return;
            lastSentNs = now;
            lastNativeStartMs = SystemClock.elapsedRealtime();
            ndi.send(frame, outWidth, outHeight, stride);
            lastNativeEndMs = SystemClock.elapsedRealtime();
            ndiFramesSent++;
            frameCount++;
            long elapsed = SystemClock.elapsedRealtime() - statsStartMs;
            if (elapsed >= 1000) {
                double fps = frameCount * 1000.0 / elapsed;
                int receivers = ndi.connections();
                String status = String.format(Locale.US, "%d × %d · %.1f fps · %d receptor(es)", outWidth, outHeight, fps, receivers);
                main.post(() -> { if (streaming && !settingsVisible) cameraStatus.setText(status); });
                frameCount = 0;
                statsStartMs = SystemClock.elapsedRealtime();
            }
        } catch (RuntimeException | LinkageError error) {
            streaming = false;
            if (ndi != null) ndi.stop();
            main.post(() -> { stopNdi(); setNdiMessage("Error de transmisión · " + error.getMessage()); });
        } finally { image.close(); }
    }

    private void startNdi() {
        if (!cameraReady) { toast("Primero habilitá la cámara."); return; }
        if (!new File(getApplicationInfo().nativeLibraryDir, "libndi.so").isFile()) {
            new AlertDialog.Builder(this).setTitle("Falta el SDK de NDI")
                .setMessage("Esta compilación permite probar cámara y OSC. Para transmitir video hay que incorporar libndi.so del SDK oficial para Android y recompilar el APK.\n\nNDI® is a registered trademark of Vizrt NDI AB.")
                .setPositiveButton("Entendido", null)
                .setNeutralButton("Obtener SDK", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video/for-developers/ndi-sdk/download/")))).show();
            return;
        }
        starting = true;
        final int generation = ++ndiGeneration;
        if (streamButton != null) streamButton.setEnabled(false);
        setNdiMessage("Iniciando NDI…");
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("abismo-ndi-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        String sourceName = config.sourceName;
        int fps = config.fps;
        boolean multicast = config.ndiMulticast;
        File ndiConfigDirectory = new File(getFilesDir(), "ndi");
        cameraExecutor.execute(() -> {
            String error = null;
            try { if (ndi == null) ndi = new NdiSender(); ndi.start(sourceName, fps, ndiConfigDirectory, multicast); }
            catch (RuntimeException | LinkageError failure) { error = failure.toString(); }
            String failureText = error;
            main.post(() -> {
                if (generation != ndiGeneration || isDestroyed()) return;
                if (!starting || !resumed || !ndiWanted) { stopNdi(); return; }
                starting = false;
                if (streamButton != null) streamButton.setEnabled(true);
                if (failureText != null) { stopNdi(); setNdiMessage("NDI no disponible"); toast(failureText); return; }
                lastSentNs = 0;
                frameCount = 0;
                statsStartMs = SystemClock.elapsedRealtime();
                streaming = true;
                if (streamButton != null) streamButton.setText("Detener NDI");
                setNdiMessage("● NDI activo · " + (multicast ? "Multicast habilitado" : "Unicast") + "\n" + sourceName);
            });
        });
    }

    private void stopNdi() {
        ndiGeneration++;
        starting = false;
        streaming = false;
        if (!cameraExecutor.isShutdown()) cameraExecutor.execute(() -> { if (ndi != null) ndi.stop(); });
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
        setNdiMessage("NDI detenido");
        if (streamButton != null) { streamButton.setText("Iniciar NDI"); streamButton.setEnabled(true); }
    }

    private void setNdiMessage(String message) {
        ndiMessage = message;
        if (ndiStatus != null) ndiStatus.setText(message);
        if (ndiIndicator != null) { ndiIndicator.setText(streaming ? "● NDI" : "○ NDI"); ndiIndicator.setContentDescription(message); }
    }

    private void queueOsc(List<Osc.Destination> targets, String address, int value) {
        oscExecutor.execute(() -> {
            Osc.Result result = Osc.send(targets, address, value);
            String report = address + " " + value + " → " + result.sent + " destino(s) · UDP sin confirmación";
            if (!result.errors.isEmpty()) report += "\nError: " + String.join("; ", result.errors);
            String message = report;
            main.post(() -> {
                oscMessage = message;
                if (!settingsVisible && oscStatus != null) oscStatus.setText(message);
            });
        });
    }

    private void beginCountdown() {
        if (sequence.isBusy() || settingsVisible || !resumed) return;
        if (!cameraReady) { toast("Esperá a que la cámara esté disponible."); return; }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        captureGeneration++;
        sequence.start(SystemClock.elapsedRealtime());
        trigger.setEnabled(false);
        settingsButton.setEnabled(false);
        // No tocar la cámara ni el emisor. Solo cambia la capa de presentación.
        main.removeCallbacks(countdownTick);
        main.post(countdownTick);
    }

    private void acceptSnapshot(int generation, Bitmap photo) {
        android.util.Log.d("AbismoCapture", "ready " + generation + " current=" + captureGeneration + " state=" + sequence.state());
        if (generation != captureGeneration || !resumed || settingsVisible || isDestroyed()) return;
        if (!sequence.frameReady(SystemClock.elapsedRealtime())) return;
        frozenFrame.setImageBitmap(photo);
        frozenFrame.setVisibility(View.VISIBLE);
        captureStatus.setText("Guardando foto…");
        flash.animate().cancel();
        flash.setAlpha(1f); flash.setVisibility(View.VISIBLE);
        flash.animate().alpha(0f).setDuration(CaptureSequence.FLASH_MS).withEndAction(() -> flash.setVisibility(View.GONE)).start();
        // Persistencia en otro executor: ni el JPEG ni MediaStore bloquean NDI.
        android.content.Context app = getApplicationContext();
        photoExecutor.execute(() -> {
            try {
                Uri saved = PhotoStore.save(app, photo);
                Bitmap thumb = Bitmap.createScaledBitmap(photo, 120, Math.max(1, photo.getHeight() * 120 / photo.getWidth()), true);
                main.post(() -> {
                    if (isDestroyed()) return;
                    lastPhotoUri = saved; lastThumbnail = thumb;
                    if (!settingsVisible) {
                        lastPhoto.setImageBitmap(thumb); lastPhoto.setVisibility(View.VISIBLE);
                        if (generation == captureGeneration) captureStatus.setText("Foto guardada");
                    }
                });
            } catch (java.io.IOException | RuntimeException failure) {
                main.post(() -> { if (!isDestroyed()) toast("No se pudo guardar la foto: " + failure.getMessage()); });
            }
        });
    }

    private void captureFailed(int generation, RuntimeException failure) {
        android.util.Log.e("AbismoCapture", "No se pudo copiar el cuadro", failure);
        if (generation != captureGeneration || isDestroyed()) return;
        cancelCapture();
        toast("No se pudo capturar la foto: " + failure.getMessage());
    }

    private void restoreLiveView() {
        pendingCapture.set(null);
        if (frozenFrame != null) { frozenFrame.setVisibility(View.GONE); frozenFrame.setImageDrawable(null); }
        if (flash != null) { flash.animate().cancel(); flash.setVisibility(View.GONE); }
        if (trigger != null) trigger.setEnabled(true);
        if (settingsButton != null) settingsButton.setEnabled(true);
        if (captureStatus != null) captureStatus.setText("Soltá el botón para iniciar · 5 s");
    }

    private void cancelCapture() {
        captureGeneration++;
        pendingCapture.set(null);
        main.removeCallbacks(countdownTick);
        sequence.cancel();
        if (trigger != null) trigger.cancelTouch();
    }

    private String targetSummary() {
        if (config.broadcast) return "BROADCAST · " + (config.broadcastIp.isEmpty() ? "sin configurar" : config.broadcastIp + ":" + config.broadcastPort);
        long count = config.destinations.stream().filter(d -> d.enabled).count();
        return "OSC · " + count + " destino(s) habilitado(s) · " + config.address;
    }

    private void showSettings() {
        if (settingsVisible || sequence.isBusy()) return;
        main.removeCallbacks(openSettings);
        settingsButton.setPressed(false);
        cancelCapture();
        settingsVisible = true;
        LinearLayout root = root();
        LinearLayout top = row();
        top.addView(label("Configuración", 24, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("Volver", false);
        back.setOnClickListener(v -> showCamera());
        top.addView(back);
        root.addView(top);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        scroll.addView(content);
        content.addView(section("01 / CÁMARA"));
        content.addView(label("Fuente NDI: abismoCam", 14, TEXT));
        streamButton = button(streaming ? "Detener NDI" : "Iniciar NDI", false);
        streamButton.setId(R.id.ndi_toggle);
        streamButton.setEnabled(!starting);
        streamButton.setOnClickListener(v -> { ndiWanted = !(streaming || starting); if (ndiWanted) startNdi(); else stopNdi(); });
        content.addView(streamButton);
        content.addView(label("Transporte NDI", 13, TEXT));
        android.widget.Spinner transport = new android.widget.Spinner(this);
        transport.setId(R.id.ndi_transport);
        transport.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] {"Unicast", "Multicast"}));
        transport.setSelection(config.ndiMulticast ? 1 : 0);
        content.addView(transport);
        content.addView(label("Al guardar un cambio de transporte se reinicia NDI: los receptores pueden mostrar negro o el último cuadro mientras reconectan. Multicast requiere una red compatible; cada receptor negocia el modo y puede seguir usando unicast.", 12, MUTED));
        CheckBox front = check("Usar cámara frontal", config.frontCamera);
        content.addView(front);
        content.addView(label("Resolución", 13, TEXT));
        android.widget.Spinner resolution = new android.widget.Spinner(this);
        resolution.setId(R.id.resolution);
        resolution.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] {"nHD · 640 × 360", "qHD · 960 × 540", "HD · 1280 × 720"}));
        resolution.setSelection(config.width == 640 ? 0 : config.width == 960 ? 1 : 2);
        content.addView(resolution);
        content.addView(label("Fotogramas por segundo", 13, TEXT));
        android.widget.Spinner fpsChoice = new android.widget.Spinner(this);
        fpsChoice.setId(R.id.fps);
        fpsChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] {"15 fps", "30 fps"}));
        fpsChoice.setSelection(config.fps == 15 ? 0 : 1);
        content.addView(fpsChoice);
        content.addView(label("Video vertical: se intercambian ancho y alto. Cambiar cámara, resolución o FPS reinicia brevemente el video al guardar.", 12, MUTED));
        content.addView(section("02 / MENSAJE OSC"));
        EditText address = input(content, "Dirección OSC", config.address, false);
        content.addView(label("Soltar inicia 5 s: OSC 1 al comenzar y OSC 0 al finalizar. Flash y foto congelada 1 s solo en pantalla.", 13, ACCENT));
        content.addView(label("Las fotos se guardan en Pictures/AbismoCam. Frontal espejada en pantalla y foto; NDI siempre sin espejo.", 12, MUTED));
        content.addView(section("03 / DESTINOS"));
        RadioGroup mode = new RadioGroup(this);
        RadioButton individual = radio("Computadoras individuales", R.id.mode_individual);
        RadioButton broadcast = radio("Broadcast a la subred", R.id.mode_broadcast);
        mode.addView(individual); mode.addView(broadcast);
        mode.check(config.broadcast ? R.id.mode_broadcast : R.id.mode_individual);
        content.addView(mode);
        LinearLayout destinationBox = column();
        content.addView(destinationBox);
        List<Osc.Destination> draft = new ArrayList<>(config.destinations);
        refreshDestinations(destinationBox, draft);
        Button add = button("+ Agregar computadora", false);
        destinationBox.addView(add);
        add.setOnClickListener(v -> editDestination(draft, -1, () -> { refreshDestinations(destinationBox, draft); destinationBox.addView(add); }));
        LinearLayout broadcastBox = column();
        content.addView(broadcastBox);
        EditText ip = input(broadcastBox, "Dirección de broadcast", config.broadcastIp, false);
        ip.setHint("Ejemplo: 192.168.1.255");
        EditText port = input(broadcastBox, "Puerto OSC", Integer.toString(config.broadcastPort), true);
        Button detect = button("Detectar broadcast de Wi-Fi / Ethernet", false);
        broadcastBox.addView(detect);
        detect.setOnClickListener(v -> {
            String detected = detectBroadcast();
            if (detected == null) toast("No se encontró una subred IPv4 local. Ingresá el broadcast manualmente.");
            else ip.setText(detected);
        });
        broadcastBox.addView(label("Todos los receptores deben escuchar el mismo puerto. La red debe permitir broadcast.", 12, MUTED));
        mode.setOnCheckedChangeListener((group, checked) -> {
            destinationBox.setVisibility(checked == R.id.mode_individual ? View.VISIBLE : View.GONE);
            broadcastBox.setVisibility(checked == R.id.mode_broadcast ? View.VISIBLE : View.GONE);
        });
        destinationBox.setVisibility(config.broadcast ? View.GONE : View.VISIBLE);
        broadcastBox.setVisibility(config.broadcast ? View.VISIBLE : View.GONE);
        TextView error = label("", 13, 0xffffb4a9);
        content.addView(error);
        Button save = button("Guardar y volver a cámara", true);
        save.setId(R.id.save_settings);
        content.addView(save, spaced(-1, 56));
        save.setOnClickListener(v -> {
            try {
                String addressText = address.getText().toString().trim();
                Osc.validateAddress(addressText);
                String ipText = ip.getText().toString().trim();
                boolean isBroadcast = mode.getCheckedRadioButtonId() == R.id.mode_broadcast;
                if (isBroadcast || !ipText.isEmpty()) Osc.ipv4(ipText);
                int portValue = parsePort(port);
                int width = new int[] {640, 960, 1280}[resolution.getSelectedItemPosition()];
                int fps = fpsChoice.getSelectedItemPosition() == 0 ? 15 : 30;
                boolean reconfigure = config.width != width || config.fps != fps || config.frontCamera != front.isChecked();
                boolean transportChanged = config.ndiMulticast != (transport.getSelectedItemPosition() == 1);
                if (reconfigure || transportChanged) stopNdi();
                config.ndiMulticast = transport.getSelectedItemPosition() == 1;
                config.sourceName = "abismoCam"; config.address = addressText;
                config.broadcast = isBroadcast; config.broadcastIp = ipText; config.broadcastPort = portValue;
                config.frontCamera = front.isChecked();
                config.width = width;
                config.height = width * 9 / 16;
                config.fps = fps;
                config.destinations.clear(); config.destinations.addAll(draft);
                config.save(this);
                oscMessage = "Configuración guardada. Listo para probar OSC.";
                showCamera();
                oscStatus.setText(targetSummary());
                if (reconfigure) bindCamera();
                else if (transportChanged && ndiWanted) startNdi();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        });
        content.addView(section("ACERCA DE ESTA PRUEBA"));
        content.addView(label("abismoCam · 0.4.1 · Lisandro Peralta\nNDI automático al abrir. Continúa durante el conteo, la foto y los ajustes. Se detiene al salir de la app. OSC no tiene confirmación de recepción.\nNDI® is a registered trademark of Vizrt NDI AB.", 12, MUTED));
        Button ndiInfo = button("NDI® · ndi.video", false);
        ndiInfo.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video"))));
        content.addView(ndiInfo);
    }

    private void refreshDestinations(LinearLayout container, List<Osc.Destination> draft) {
        container.removeAllViews();
        if (draft.isEmpty()) container.addView(label("Todavía no hay computadoras. Agregá la IP y el puerto de cada receptor.", 13, MUTED));
        for (int i = 0; i < draft.size(); i++) {
            final int index = i;
            Osc.Destination d = draft.get(i);
            LinearLayout row = row();
            CheckBox enabled = check(d.name + "\n" + d.ip + ":" + d.port, d.enabled);
            enabled.setOnCheckedChangeListener((v, checked) -> draft.set(index, new Osc.Destination(d.name, d.ip, d.port, checked)));
            row.addView(enabled, new LinearLayout.LayoutParams(0, -2, 1));
            Button edit = button("Editar", false);
            edit.setOnClickListener(v -> {
                View add = container.getChildAt(container.getChildCount() - 1);
                editDestination(draft, index, () -> { refreshDestinations(container, draft); container.addView(add); });
            });
            row.addView(edit);
            container.addView(row);
        }
    }

    private void editDestination(List<Osc.Destination> draft, int index, Runnable changed) {
        Osc.Destination existing = index < 0 ? null : draft.get(index);
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText name = input(form, "Nombre", existing == null ? "Computadora " + (draft.size() + 1) : existing.name, false);
        EditText ip = input(form, "IP de la computadora", existing == null ? "" : existing.ip, false);
        ip.setHint("192.168.1.20");
        EditText port = input(form, "Puerto OSC", existing == null ? "9000" : Integer.toString(existing.port), true);
        TextView error = label("", 12, 0xffffb4a9); form.addView(error);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(index < 0 ? "Agregar computadora" : "Editar computadora")
            .setView(form).setPositiveButton("Guardar", null).setNegativeButton("Cancelar", null);
        if (index >= 0) builder.setNeutralButton("Eliminar", (d, w) -> { draft.remove(index); changed.run(); });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Osc.Destination next = new Osc.Destination(name.getText().toString(), ip.getText().toString().trim(), parsePort(port), existing == null || existing.enabled);
                for (int j = 0; j < draft.size(); j++) if (j != index && draft.get(j).ip.equals(next.ip) && draft.get(j).port == next.port)
                    throw new IllegalArgumentException("Ese destino ya está en la lista.");
                if (index < 0) draft.add(next); else draft.set(index, next);
                changed.run(); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        }));
        dialog.show();
    }

    private String detectBroadcast() {
        ConnectivityManager manager = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (!(link.getAddress() instanceof Inet4Address) || link.getPrefixLength() > 30 || link.getPrefixLength() < 1) continue;
                byte[] bytes = link.getAddress().getAddress();
                int value = ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
                int broadcast = value | (-1 >>> link.getPrefixLength());
                try { return InetAddress.getByAddress(new byte[] {(byte)(broadcast >>> 24), (byte)(broadcast >>> 16), (byte)(broadcast >>> 8), (byte)broadcast}).getHostAddress(); }
                catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
            }
        }
        return null;
    }

    private int parsePort(EditText input) {
        try {
            int port = Integer.parseInt(input.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) { throw new IllegalArgumentException("El puerto debe estar entre 1 y 65535."); }
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (cameraReady && ndiWanted && !streaming && !starting) startNdi();
        if (config != null && !settingsVisible && !cameraReady && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera();
    }
    @Override protected void onPause() { main.removeCallbacks(openSettings); resumed = false; cancelCapture(); stopNdi(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus) { main.removeCallbacks(openSettings); if (trigger != null) trigger.cancelTouch(); } }
    @Override protected void onDestroy() {
        cancelCapture(); stopNdi(); cameraGeneration++;
        if (cameraProvider != null) cameraProvider.unbindAll();
        // Esperar los callbacks de cámara antes de cerrar el executor de fotos.
        cameraExecutor.execute(() -> { if (ndi != null) ndi.close(); photoExecutor.shutdown(); });
        cameraExecutor.shutdown(); oscExecutor.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private TextView section(String text) {
        TextView v = label(text, 12, ACCENT); v.setTypeface(null, Typeface.BOLD); v.setPadding(0, dp(24), 0, dp(10)); return v;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Button button(String text, boolean primary) {
        Button v = new Button(this); v.setText(text); v.setAllCaps(false); v.setTextColor(primary ? BG : TEXT);
        v.setTextSize(13); v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48)); v.setPadding(dp(14), dp(6), dp(14), dp(6));
        v.setBackground(background(primary ? ACCENT : PANEL, 14)); return v;
    }
    private CheckBox check(String text, boolean value) { CheckBox v = new CheckBox(this); v.setText(text); v.setTextColor(TEXT); v.setChecked(value); v.setMinHeight(dp(48)); return v; }
    private RadioButton radio(String text, int id) { RadioButton v = new RadioButton(this); v.setId(id); v.setText(text); v.setTextColor(TEXT); return v; }
    private EditText input(LinearLayout parent, String title, String value, boolean number) {
        TextView titleView = label(title, 12, MUTED); titleView.setPadding(0, dp(10), 0, dp(4)); parent.addView(titleView);
        EditText v = new EditText(this); v.setTextColor(TEXT); v.setHintTextColor(MUTED); v.setSingleLine(true); v.setTextSize(16);
        v.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        v.setText(value); v.setPadding(dp(12), dp(10), dp(12), dp(10)); v.setBackground(background(PANEL, 10));
        v.setContentDescription(title); parent.addView(v, new LinearLayout.LayoutParams(-1, dp(50))); return v;
    }
    private LinearLayout.LayoutParams spaced(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(height)); params.setMargins(0, dp(12), 0, dp(12)); return params;
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}

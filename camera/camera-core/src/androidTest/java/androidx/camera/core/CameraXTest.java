/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.camera.core;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.app.Instrumentation;
import android.content.Context;

import androidx.camera.core.impl.CameraControlInternal;
import androidx.camera.core.impl.CameraFactory;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.core.impl.CameraInternal;
import androidx.camera.core.impl.ExtendableUseCaseConfigFactory;
import androidx.camera.core.impl.UseCaseConfigFactory;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.testing.fakes.FakeCamera;
import androidx.camera.testing.fakes.FakeCameraDeviceSurfaceManager;
import androidx.camera.testing.fakes.FakeCameraFactory;
import androidx.camera.testing.fakes.FakeCameraInfoInternal;
import androidx.camera.testing.fakes.FakeLifecycleOwner;
import androidx.camera.testing.fakes.FakeUseCase;
import androidx.camera.testing.fakes.FakeUseCaseConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public final class CameraXTest {
    @CameraSelector.LensFacing
    private static final int CAMERA_LENS_FACING = CameraSelector.LENS_FACING_BACK;
    @CameraSelector.LensFacing
    private static final int CAMERA_LENS_FACING_FRONT = CameraSelector.LENS_FACING_FRONT;
    private static final CameraSelector CAMERA_SELECTOR =
            new CameraSelector.Builder().requireLensFacing(CAMERA_LENS_FACING).build();
    private static final String CAMERA_ID = "0";
    private static final String CAMERA_ID_FRONT = "1";

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private Context mContext;
    private CameraInternal mCameraInternal;
    private FakeLifecycleOwner mLifecycle;
    private CameraXConfig.Builder mConfigBuilder;
    private FakeCameraFactory mFakeCameraFactory;
    private UseCaseConfigFactory mUseCaseConfigFactory;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();

        ExtendableUseCaseConfigFactory defaultConfigFactory = new ExtendableUseCaseConfigFactory();
        defaultConfigFactory.installDefaultProvider(FakeUseCaseConfig.class,
                cameraInfo -> new FakeUseCaseConfig.Builder().getUseCaseConfig());
        mUseCaseConfigFactory = defaultConfigFactory;
        mFakeCameraFactory = new FakeCameraFactory();
        mCameraInternal = new FakeCamera(mock(CameraControlInternal.class),
                new FakeCameraInfoInternal(0, CAMERA_LENS_FACING));
        mFakeCameraFactory.insertCamera(CAMERA_LENS_FACING, CAMERA_ID, () -> mCameraInternal);
        mConfigBuilder =
                new CameraXConfig.Builder()
                        .setCameraFactoryProvider((ignored0, ignored1) -> mFakeCameraFactory)
                        .setDeviceSurfaceManagerProvider(ignored ->
                                new FakeCameraDeviceSurfaceManager())
                        .setUseCaseConfigFactoryProvider(ignored -> mUseCaseConfigFactory);

        mLifecycle = new FakeLifecycleOwner();
    }

    @After
    public void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
        CameraX.shutdown().get(10000, TimeUnit.MILLISECONDS);
    }


    @Test
    public void initDeinit_success() throws ExecutionException, InterruptedException {
        CameraX.initialize(mContext, mConfigBuilder.build()).get();
        assertThat(CameraX.isInitialized()).isTrue();

        CameraX.shutdown().get();
        assertThat(CameraX.isInitialized()).isFalse();
    }

    @Test
    public void failInit_shouldInDeinitState() throws InterruptedException {
        // Create an empty config to cause a failed init.
        CameraXConfig cameraXConfig = new CameraXConfig.Builder().build();
        Exception exception = null;
        try {
            CameraX.initialize(mContext, cameraXConfig).get();
        } catch (ExecutionException e) {
            exception = e;
        }
        assertThat(exception).isInstanceOf(ExecutionException.class);
        assertThat(CameraX.isInitialized()).isFalse();
    }

    @Test
    public void reinit_success() throws ExecutionException, InterruptedException {
        CameraX.initialize(mContext, mConfigBuilder.build()).get();
        assertThat(CameraX.isInitialized()).isTrue();

        CameraX.shutdown().get();
        assertThat(CameraX.isInitialized()).isFalse();

        CameraX.initialize(mContext, mConfigBuilder.build()).get();
        assertThat(CameraX.isInitialized()).isTrue();
    }

    @Test
    public void failedInit_doesNotRequireReconfigure() throws InterruptedException {
        // Create an empty config to cause a failed init.
        CameraXConfig cameraXConfig = new CameraXConfig.Builder().build();
        Exception exception = null;
        CameraX.configureInstance(cameraXConfig);
        boolean firstInitFailed = false;
        try {
            CameraX.getOrCreateInstance(mContext).get();
        } catch (ExecutionException e) {
            firstInitFailed = true;
        }

        // Does not throw IllegalStateException (though initialization will fail)
        boolean secondInitFailed = false;
        try {
            CameraX.getOrCreateInstance(mContext).get();
        } catch (ExecutionException e) {
            secondInitFailed = true;
        }
        assertThat(firstInitFailed).isTrue();
        assertThat(secondInitFailed).isTrue();
    }

    @Test(expected = IllegalStateException.class)
    public void cannotConfigureTwice() {
        CameraX.configureInstance(mConfigBuilder.build());
        CameraX.configureInstance(mConfigBuilder.build());
    }

    @Test
    public void shutdown_clearsPreviousConfiguration()
            throws ExecutionException, InterruptedException {
        CameraX.configureInstance(mConfigBuilder.build());

        // Clear the configuration so we can reinit
        CameraX.shutdown().get();

        // Should not throw
        CameraX.configureInstance(mConfigBuilder.build());
    }

    @Test
    public void initDeinit_withDirectExecutor() {
        mConfigBuilder.setCameraExecutor(CameraXExecutors.directExecutor());

        // Don't call Future.get() because its behavior should be the same as synchronous call.
        CameraX.initialize(mContext, mConfigBuilder.build());
        assertThat(CameraX.isInitialized()).isTrue();

        CameraX.shutdown();
        assertThat(CameraX.isInitialized()).isFalse();
    }

    @Test
    public void initDeinit_withMultiThreadExecutor()
            throws ExecutionException, InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(3);
        mConfigBuilder.setCameraExecutor(executorService);

        CameraX.initialize(mContext, mConfigBuilder.build()).get();
        assertThat(CameraX.isInitialized()).isTrue();

        CameraX.shutdown().get();
        assertThat(CameraX.isInitialized()).isFalse();

        executorService.shutdown();
    }

    @Test
    public void init_withDifferentCameraXConfig() throws ExecutionException, InterruptedException {
        CameraFactory cameraFactory0 = new FakeCameraFactory();
        CameraFactory.Provider cameraFactoryProvider0 = (ignored0, ignored1) -> cameraFactory0;
        CameraFactory cameraFactory1 = new FakeCameraFactory();
        CameraFactory.Provider cameraFactoryProvider1 = (ignored0, ignored1) -> cameraFactory1;

        mConfigBuilder.setCameraFactoryProvider(cameraFactoryProvider0);
        CameraX.initialize(mContext, mConfigBuilder.build());
        CameraX cameraX0 = CameraX.getOrCreateInstance(mContext).get();

        assertThat(cameraX0.getCameraFactory()).isEqualTo(cameraFactory0);

        CameraX.shutdown();

        mConfigBuilder.setCameraFactoryProvider(cameraFactoryProvider1);
        CameraX.initialize(mContext, mConfigBuilder.build());
        CameraX cameraX1 = CameraX.getOrCreateInstance(mContext).get();

        assertThat(cameraX1.getCameraFactory()).isEqualTo(cameraFactory1);
    }

    @Test
    public void requestingDefaultConfiguration_returnsDefaultConfiguration() {
        initCameraX();
        // Requesting a default configuration will throw if CameraX is not initialized.
        FakeUseCaseConfig config = CameraX.getDefaultUseCaseConfig(FakeUseCaseConfig.class, null);
        assertThat(config).isNotNull();
        assertThat(config.getTargetClass(null)).isEqualTo(FakeUseCase.class);
    }





    @Test
    public void canRetrieveCameraInfo() {
        initCameraX();
        CameraInfoInternal cameraInfoInternal = CameraX.getCameraInfo(CAMERA_ID);
        assertThat(cameraInfoInternal).isNotNull();
        assertThat(cameraInfoInternal.getLensFacing()).isEqualTo(CAMERA_LENS_FACING);
    }

    @Test
    public void canGetCameraXContext() {
        initCameraX();
        Context context = CameraX.getContext();
        assertThat(context).isNotNull();
    }

    @Test(expected = IllegalArgumentException.class)
    public void cameraInfo_cannotRetrieveCameraInfo_forFrontCamera() {
        initCameraX();
        // Expect throw the IllegalArgumentException when try to get the cameraInfo from the camera
        // which does not exist.
        CameraX.getCameraInfo(CAMERA_ID_FRONT);
    }

    @Test
    public void checkHasCameraTrueForExistentCamera() throws CameraInfoUnavailableException {
        initCameraX();
        assertThat(CameraX.hasCamera(
                new CameraSelector.Builder().requireLensFacing(
                        CameraSelector.LENS_FACING_BACK).build())).isTrue();
    }

    @Test
    public void checkHasCameraFalseForNonexistentCamera() throws CameraInfoUnavailableException {
        initCameraX();
        assertThat(CameraX.hasCamera(new CameraSelector.Builder().requireLensFacing(
                CameraSelector.LENS_FACING_BACK).requireLensFacing(
                CameraSelector.LENS_FACING_FRONT).build())).isFalse();
    }

    private void initCameraX() {
        CameraX.initialize(mContext, mConfigBuilder.build());
    }
}

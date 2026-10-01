package de.danoeh.antennapod.playback.service.internal;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public class ExoPlayerUtilsTest {
    @Test
    public void captureHttpSourceDoesNotReadErrorResponseBody() throws Exception {
        AtomicInteger openedBodies = new AtomicInteger();
        HttpURLConnection connection = new HttpURLConnection(new URL("https://example.com/audio")) {
            @Override
            public int getResponseCode() {
                return 500;
            }

            @Override
            public InputStream getInputStream() {
                openedBodies.incrementAndGet();
                throw new AssertionError();
            }

            @Override
            public InputStream getErrorStream() {
                openedBodies.incrementAndGet();
                throw new AssertionError();
            }

            @Override
            public void disconnect() {
            }

            @Override
            public boolean usingProxy() {
                return false;
            }

            @Override
            public void connect() {
            }
        };
        ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource source =
                new ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource(
                        null, url -> connection);
        try {
            try {
                source.open(new DataSpec(Uri.parse("https://example.com/audio")));
                fail("Expected HTTP failure");
            } catch (IOException expected) {
                assertEquals("HTTP response code: 500", expected.getMessage());
            } finally {
                source.close();
            }
        } finally {
            source.close();
        }
        assertEquals(0, openedBodies.get());
    }

    @Test
    public void captureHttpSourceDisconnectsAfterRuntimeCloseFailure() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        InputStream input = mock(InputStream.class);
        IllegalStateException failure = new IllegalStateException("Unbalanced enter/exit");
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(input);
        doThrow(failure).when(input).close();
        ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource source =
                new ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource(null, url -> connection);
        source.open(new DataSpec(Uri.parse("https://example.com/audio")));

        try {
            source.close();
            fail("Expected close failure");
        } catch (IOException expected) {
            assertSame(failure, expected.getCause());
        }
        source.close();
        verify(input).close();
        verify(connection).disconnect();
    }

    @Test
    public void captureHttpSourceWrapsRuntimeDisconnectFailure() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        InputStream input = mock(InputStream.class);
        IllegalStateException failure = new IllegalStateException("Unbalanced enter/exit");
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(input);
        doThrow(failure).when(connection).disconnect();
        ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource source =
                new ExoPlayerUtils.ApMediaSourceFactory.CaptureHttpDataSource(null, url -> connection);
        source.open(new DataSpec(Uri.parse("https://example.com/audio")));

        try {
            source.close();
            fail("Expected disconnect failure");
        } catch (IOException expected) {
            assertSame(failure, expected.getCause());
        }
        source.close();
        verify(input).close();
        verify(connection).disconnect();
    }
}

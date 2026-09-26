package ar.com.biopus.abismocam;

import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Configuración oficial del SDK, privada a esta app y escrita antes de inicializarlo. */
final class NdiTransport {
    static String json(boolean multicast) {
        return "{\"ndi\":{\"multicast\":{\"send\":{\"enable\":" + multicast
            + ",\"ttl\":1,\"netprefix\":\"239.255.0.0\",\"netmask\":\"255.255.0.0\"}}}}";
    }

    static void configure(File directory, boolean multicast) {
        AtomicFile file = new AtomicFile(new File(directory, "ndi-config.v1.json"));
        FileOutputStream output = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No se pudo crear la configuración NDI.");
            output = file.startWrite();
            output.write(json(multicast).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output); output = null;
            Os.setenv("NDI_CONFIG_DIR", directory.getAbsolutePath(), true);
        } catch (IOException | ErrnoException failure) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("No se pudo configurar el transporte NDI", failure);
        }
    }
}

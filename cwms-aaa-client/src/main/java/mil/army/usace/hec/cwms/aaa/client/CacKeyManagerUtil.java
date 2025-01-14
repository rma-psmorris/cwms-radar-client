/*
 * MIT License
 *
 * Copyright (c) 2022 Hydrologic Engineering Center
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mil.army.usace.hec.cwms.aaa.client;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;


public final class CacKeyManagerUtil {
    private static KeyManager instance;

    private CacKeyManagerUtil() {
        throw new AssertionError("Utility class");
    }

    private static KeyManager createKeyManager() throws CacCertificateException {
        return getKeyManagerFromWindowsKeyStore();
    }

    private static CacKeyManager getKeyManagerFromWindowsKeyStore() throws CacCertificateException {
        try {
            KeyStore keystore = KeyStore.getInstance("WINDOWS-MY");
            keystore.load(null, null);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keystore, null);
            KeyManager[] kms = kmf.getKeyManagers();
            for (KeyManager km : kms) {
                if (km instanceof X509KeyManager) {
                    return new CacKeyManager((X509KeyManager) km, keystore);
                }
            }
            throw new CacCertificateException("Failed to get X509KeyManager from Windows OS");
        } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException | IOException | CertificateException e) {
            throw new CacCertificateException("Failed to get X509KeyManager from Windows OS", e);
        }
    }

    public static synchronized KeyManager getKeyManager() throws CacCertificateException {
        if (instance == null) {
            instance = createKeyManager();
        }
        return instance;
    }

}

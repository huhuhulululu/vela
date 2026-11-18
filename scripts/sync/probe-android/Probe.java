import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/** 在 API 33+ 设备上探测 Ed25519：PKCS#8 种子导入、签名、取公钥、验签。 */
public class Probe {
    static String pubkeyHow = "";
    static String algName = "Ed25519";
    static String providerName = "";

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "probe-vectors.json";
        System.out.println("SDK " + Build.VERSION.SDK_INT);
        System.out.println("RELEASE " + Build.VERSION.RELEASE);
        JSONObject root = new JSONObject(read(path));
        byte[] pkcs8 = hex(root.getString("pkcs8_hex"));
        byte[] expectPub = hex(root.getString("pub_raw_hex"));
        byte[] x509 = hex(root.getString("pub_x509_hex"));
        JSONArray cases = root.getJSONArray("cases");

        dumpProviders();
        KeyFactory kf = null;
        String[] names = {"Ed25519", "ED25519", "EdDSA", "1.3.101.112"};
        String[] providers = providerNames();
        Exception last = null;
        for (String name : names) {
            try {
                kf = KeyFactory.getInstance(name);
                algName = name;
                providerName = kf.getProvider().getName();
                System.out.println("KEYFACTORY " + name + " " + providerName);
                break;
            } catch (Exception e) {
                last = e;
            }
            for (String p : providers) {
                try {
                    kf = KeyFactory.getInstance(name, p);
                    algName = name;
                    providerName = p;
                    System.out.println("KEYFACTORY " + name + " " + p);
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (kf != null) break;
        }
        if (kf == null) {
            System.out.println("IMPORT FAIL " + last);
            failRest();
            return;
        }
        PrivateKey priv;
        try {
            priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            System.out.println("IMPORT PASS " + priv.getClass().getName());
        } catch (Exception e) {
            System.out.println("IMPORT FAIL " + e);
            failRest();
            return;
        }
        dumpKey(priv, kf);

        JSONObject signCase = cases.getJSONObject(0);
        JSONObject checkCase = cases.getJSONObject(1);
        byte[] signMsg = hex(signCase.getString("msg_hex"));
        byte[] signSig = b64(signCase.getString("sig_b64"));
        byte[] checkMsg = hex(checkCase.getString("msg_hex"));
        byte[] checkSig = b64(checkCase.getString("sig_b64"));

        byte[] androidSign = sign(priv, signMsg, "SIGN", "ANDROID_SIG_SIGN");
        byte[] androidCheck = sign(priv, checkMsg, "PUBCHECK_SIGN", "ANDROID_SIG_PUBCHECK");

        byte[] got = extractPublic(priv, kf);
        if (got != null && Arrays.equals(got, expectPub)) {
            System.out.println("PUBKEY PASS " + pubkeyHow);
        } else if (got != null) {
            System.out.println("PUBKEY FAIL mismatch " + pubkeyHow);
        } else {
            System.out.println("PUBKEY FAIL");
        }

        PublicKey pub;
        try {
            pub = kf.generatePublic(new X509EncodedKeySpec(x509));
        } catch (Exception e) {
            System.out.println("VERIFY_GO_ON_ANDROID FAIL " + e);
            System.out.println("PUBCHECK_GO_ON_ANDROID FAIL " + e);
            return;
        }
        boolean goSign = verify(pub, signMsg, signSig);
        boolean selfSign = androidSign != null && verify(pub, signMsg, androidSign);
        System.out.println("VERIFY_GO_ON_ANDROID " + (goSign ? "PASS" : "FAIL") + " self=" + selfSign);
        boolean goCheck = verify(pub, checkMsg, checkSig);
        boolean selfCheck = androidCheck != null && verify(pub, checkMsg, androidCheck);
        System.out.println("PUBCHECK_GO_ON_ANDROID " + (goCheck ? "PASS" : "FAIL") + " self=" + selfCheck);
    }

    static void dumpProviders() {
        for (Provider p : Security.getProviders()) {
            System.out.println("PROVIDER " + p.getName());
            for (Provider.Service s : p.getServices()) {
                String type = s.getType();
                if (type.equals("KeyFactory") || type.equals("Signature") || type.equals("KeyPairGenerator")) {
                    System.out.println("SERVICE " + p.getName() + " " + type + " " + s.getAlgorithm());
                }
            }
        }
    }

    static String[] providerNames() {
        Provider[] ps = Security.getProviders();
        String[] names = new String[ps.length];
        for (int i = 0; i < ps.length; i++) names[i] = ps[i].getName();
        return names;
    }

    static void failRest() {
        System.out.println("SIGN FAIL");
        System.out.println("PUBKEY FAIL");
        System.out.println("VERIFY_GO_ON_ANDROID FAIL");
        System.out.println("PUBCHECK_GO_ON_ANDROID FAIL");
    }

    static byte[] sign(PrivateKey priv, byte[] msg, String label, String outKey) {
        try {
            Signature s = signature();
            s.initSign(priv);
            s.update(msg);
            byte[] sig = s.sign();
            System.out.println(label + " PASS");
            System.out.println(outKey + " " + b64e(sig));
            return sig;
        } catch (Exception e) {
            System.out.println(label + " FAIL " + e);
            return null;
        }
    }

    static Signature signature() throws Exception {
        if (providerName.length() == 0) return Signature.getInstance(algName);
        return Signature.getInstance(algName, providerName);
    }

    static boolean verify(PublicKey pub, byte[] msg, byte[] sig) {
        try {
            Signature s = signature();
            s.initVerify(pub);
            s.update(msg);
            return s.verify(sig);
        } catch (Exception e) {
            System.out.println("VERIFY_ERR " + e);
            return false;
        }
    }

    static void dumpKey(PrivateKey priv, KeyFactory kf) {
        System.out.println("PRIV_CLASS " + priv.getClass().getName());
        byte[] enc = priv.getEncoded();
        System.out.println("PRIV_ENCODED_LEN " + (enc == null ? -1 : enc.length));
        for (Method m : priv.getClass().getMethods()) {
            if (m.getParameterTypes().length == 0) {
                System.out.println("METHOD " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + " -> " + m.getReturnType().getSimpleName());
            }
        }
        if (priv instanceof EdECPrivateKey) {
            Optional<byte[]> bytes = ((EdECPrivateKey) priv).getBytes();
            System.out.println("EDEC_BYTES " + (bytes.isPresent() ? bytes.get().length : -1));
        }
        try {
            kf.getKeySpec(priv, EdECPrivateKeySpec.class);
            System.out.println("KEYSPEC EdECPrivateKeySpec");
        } catch (Exception e) {
            System.out.println("KEYSPEC FAIL " + e.getClass().getSimpleName());
        }
    }

    /** 只接受平台自己交出来的 32 字节公钥，不在探针里做曲线运算。 */
    static byte[] extractPublic(PrivateKey priv, KeyFactory kf) {
        byte[] enc = priv.getEncoded();
        byte[] fromDer = publicFromPkcs8(enc);
        if (fromDer != null) {
            pubkeyHow = "PrivateKey.getEncoded PKCS8 [1] BIT STRING";
            return fromDer;
        }
        for (Method m : priv.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (!PublicKey.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                PublicKey pk = (PublicKey) m.invoke(priv);
                if (pk == null) continue;
                byte[] raw = rawFromX509(pk.getEncoded());
                if (raw != null) {
                    pubkeyHow = m.getName() + "()";
                    return raw;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static byte[] rawFromX509(byte[] enc) {
        if (enc == null || enc.length < 32) return null;
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    /** RFC 8410 可选的 [1] IMPLICIT BIT STRING。 */
    static byte[] publicFromPkcs8(byte[] der) {
        if (der == null) return null;
        for (int i = 0; i + 35 < der.length; i++) {
            if ((der[i] & 0xFF) == 0x81 && (der[i + 1] & 0xFF) == 0x21 && der[i + 2] == 0x00) {
                return Arrays.copyOfRange(der, i + 3, i + 35);
            }
        }
        return null;
    }

    static String read(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static byte[] b64(String s) {
        return java.util.Base64.getUrlDecoder().decode(s);
    }

    static String b64e(byte[] b) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

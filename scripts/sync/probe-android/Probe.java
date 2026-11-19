import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Android 14 上的 P-256 探针（minSdk 26 口径）：ECPrivateKeySpec 导入、
 * SHA256withECDSA（DER 与 r‖s 互转）、BigInteger 标量乘、与 Go 互相验签、核对消息验签。
 */
public class Probe {
    static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "probe-vectors.json";
        System.out.println("SDK " + Build.VERSION.SDK_INT);
        System.out.println("RELEASE " + Build.VERSION.RELEASE);
        JSONObject root = new JSONObject(read(path));
        byte[] d = hex(root.getString("d_hex"));
        byte[] expectPub = hex(root.getString("pub_hex"));
        byte[] signMsg = hex(root.getString("sign_msg_hex"));
        byte[] checkMsg = hex(root.getString("pubcheck_msg_hex"));

        ECParameterSpec spec;
        KeyFactory kf;
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            spec = params.getParameterSpec(ECParameterSpec.class);
            kf = KeyFactory.getInstance("EC");
            System.out.println("KEYFACTORY EC " + kf.getProvider().getName());
        } catch (Exception e) {
            System.out.println("IMPORT FAIL " + e);
            failRest();
            return;
        }

        PrivateKey priv;
        try {
            priv = importScalar(kf, spec, d);
            importScalar(kf, spec, scalar(1));
            importScalar(kf, spec, scalarNm1());
            JSONArray pubs = root.getJSONArray("pubkey");
            for (int i = 0; i < pubs.length(); i++) {
                importScalar(kf, spec, hex(pubs.getJSONObject(i).getString("d_hex")));
            }
            System.out.println("IMPORT PASS " + priv.getClass().getName());
        } catch (Exception e) {
            System.out.println("IMPORT FAIL " + e);
            failRest();
            return;
        }

        int pubFail = 0;
        try {
            if (!Arrays.equals(P256.publicKey(d), expectPub)) {
                System.out.println("PUBKEY_CASE FAIL derived");
                pubFail++;
            }
        } catch (Exception e) {
            System.out.println("PUBKEY_CASE FAIL derived " + e);
            pubFail++;
        }
        JSONArray pubs = root.getJSONArray("pubkey");
        for (int i = 0; i < pubs.length(); i++) {
            JSONObject c = pubs.getJSONObject(i);
            byte[] di = hex(c.getString("d_hex"));
            byte[] want = b64(c.getString("pub_b64url"));
            try {
                if (!Arrays.equals(P256.publicKey(di), want)) {
                    System.out.println("PUBKEY_CASE FAIL " + c.getString("d_hex"));
                    pubFail++;
                }
            } catch (Exception e) {
                System.out.println("PUBKEY_CASE FAIL " + c.getString("d_hex") + " " + e);
                pubFail++;
            }
        }
        System.out.println(pubFail == 0 ? "PUBKEY PASS" : "PUBKEY FAIL");

        PublicKey pubKey;
        try {
            pubKey = publicFromRaw(kf, spec, expectPub);
        } catch (Exception e) {
            System.out.println("SIGN FAIL pubkey " + e);
            System.out.println("VERIFY_GO_ON_ANDROID FAIL " + e);
            System.out.println("PUBCHECK_GO_ON_ANDROID FAIL " + e);
            return;
        }

        byte[] androidSign = signRs(priv, signMsg, "ANDROID_SIG_SIGN");
        byte[] androidCheck = signRs(priv, checkMsg, "ANDROID_SIG_PUBCHECK");
        if (androidSign != null && platformVerify(pubKey, signMsg, androidSign)) {
            System.out.println("SIGN PASS");
        } else {
            System.out.println("SIGN FAIL");
        }
        if (androidCheck == null || !platformVerify(pubKey, checkMsg, androidCheck)) {
            System.out.println("PUBCHECK_SELF FAIL");
        }

        int[] signCounts = count(root.getJSONArray("sign"), spec, kf, true);
        int[] badCounts = count(root.getJSONArray("signInvalid"), spec, kf, false);
        System.out.println("SIGN_CASES " + signCounts[0] + " " + signCounts[1]);
        System.out.println("SIGN_INVALID_CASES " + badCounts[0] + " " + badCounts[1]);
        boolean goOnAnd = signCounts[1] == 0 && badCounts[1] == 0 && signCounts[0] > 0 && badCounts[0] > 0;
        System.out.println("VERIFY_GO_ON_ANDROID " + (goOnAnd ? "PASS" : "FAIL"));

        int[] checkCounts = count(root.getJSONArray("pubcheck"), spec, kf, false);
        System.out.println("PUBCHECK_CASES " + checkCounts[0] + " " + checkCounts[1]);
        boolean checkOk = checkCounts[1] == 0 && checkCounts[0] > 0;
        System.out.println("PUBCHECK_GO_ON_ANDROID " + (checkOk ? "PASS" : "FAIL"));
    }

    static int[] count(JSONArray arr, ECParameterSpec spec, KeyFactory kf, boolean expectAllValid) throws Exception {
        int pass = 0;
        int fail = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            boolean want = c.getBoolean("valid");
            if (expectAllValid && !want) {
                fail++;
                System.out.println("CASE FAIL unexpected invalid");
                continue;
            }
            boolean got = verifySample(spec, kf, c.getString("pub_b64url"), c.getString("message_hex"), c.getString("sig_b64url"));
            if (got == want) {
                pass++;
            } else {
                fail++;
                String why = c.has("reason") ? c.getString("reason") : c.optString("blobID", "");
                System.out.println("CASE FAIL " + why + " want=" + want + " got=" + got);
            }
        }
        return new int[]{pass, fail};
    }

    static boolean verifySample(ECParameterSpec spec, KeyFactory kf, String pubB64, String msgHex, String sigB64) {
        byte[] pub;
        byte[] sig;
        byte[] msg;
        try {
            pub = b64(pubB64);
            sig = b64(sigB64);
            msg = hex(msgHex);
        } catch (Exception e) {
            return false;
        }
        if (!formatOK(pub, sig)) {
            return false;
        }
        try {
            PublicKey pk = publicFromRaw(kf, spec, pub);
            return platformVerify(pk, msg, sig);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean formatOK(byte[] pub, byte[] sig) {
        if (sig.length != 64 || pub.length != 65 || pub[0] != 0x04) {
            return false;
        }
        if (!inRange(sig, 0) || !inRange(sig, 32)) {
            return false;
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(pub, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(pub, 33, 65));
        return P256.onCurve(x, y);
    }

    static boolean inRange(byte[] sig, int off) {
        byte[] part = Arrays.copyOfRange(sig, off, off + 32);
        BigInteger v = new BigInteger(1, part);
        return v.signum() > 0 && v.compareTo(N) < 0;
    }

    static PrivateKey importScalar(KeyFactory kf, ECParameterSpec spec, byte[] d32) throws Exception {
        if (d32.length != 32) {
            throw new IllegalArgumentException("d");
        }
        return kf.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d32), spec));
    }

    static PublicKey publicFromRaw(KeyFactory kf, ECParameterSpec spec, byte[] pub) throws Exception {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(pub, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(pub, 33, 65));
        return kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    static byte[] signRs(PrivateKey priv, byte[] msg, String outKey) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(priv);
            s.update(msg);
            byte[] der = s.sign();
            byte[] rs = derToRs(der);
            if (rs == null) {
                System.out.println(outKey + " FAIL der");
                return null;
            }
            System.out.println(outKey + " " + b64e(rs));
            return rs;
        } catch (Exception e) {
            System.out.println(outKey + " FAIL " + e);
            return null;
        }
    }

    static boolean platformVerify(PublicKey pub, byte[] msg, byte[] rs) {
        try {
            byte[] der = rsToDer(rs);
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(msg);
            return s.verify(der);
        } catch (Exception e) {
            System.out.println("VERIFY_ERR " + e);
            return false;
        }
    }

    static byte[] derToRs(byte[] der) {
        try {
            int i = 0;
            if (der[i++] != 0x30) {
                return null;
            }
            int seqLen = der[i++] & 0xff;
            if ((seqLen & 0x80) != 0 || seqLen != der.length - 2) {
                return null;
            }
            if (der[i++] != 0x02) {
                return null;
            }
            int rLen = der[i++] & 0xff;
            if (rLen < 1 || i + rLen > der.length) {
                return null;
            }
            byte[] r = Arrays.copyOfRange(der, i, i + rLen);
            i += rLen;
            if (der[i++] != 0x02) {
                return null;
            }
            int sLen = der[i++] & 0xff;
            if (sLen < 1 || i + sLen != der.length) {
                return null;
            }
            byte[] s = Arrays.copyOfRange(der, i, i + sLen);
            byte[] rs = new byte[64];
            if (!putInt(r, rs, 0) || !putInt(s, rs, 32)) {
                return null;
            }
            return rs;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean putInt(byte[] v, byte[] dst, int off) {
        if (v.length == 0 || (v[0] & 0x80) != 0) {
            return false;
        }
        int start = 0;
        if (v.length > 1 && v[0] == 0) {
            start = 1;
        }
        int n = v.length - start;
        if (n == 0 || n > 32) {
            return false;
        }
        System.arraycopy(v, start, dst, off + (32 - n), n);
        return true;
    }

    static byte[] rsToDer(byte[] rs) {
        byte[] r = derInt(rs, 0);
        byte[] s = derInt(rs, 32);
        int len = 2 + r.length + 2 + s.length;
        byte[] out = new byte[2 + len];
        out[0] = 0x30;
        out[1] = (byte) len;
        int i = 2;
        out[i++] = 0x02;
        out[i++] = (byte) r.length;
        System.arraycopy(r, 0, out, i, r.length);
        i += r.length;
        out[i++] = 0x02;
        out[i++] = (byte) s.length;
        System.arraycopy(s, 0, out, i, s.length);
        return out;
    }

    static byte[] derInt(byte[] rs, int off) {
        int start = off;
        while (start < off + 31 && rs[start] == 0) {
            start++;
        }
        boolean pad = (rs[start] & 0x80) != 0;
        int n = off + 32 - start;
        byte[] out = new byte[n + (pad ? 1 : 0)];
        System.arraycopy(rs, start, out, pad ? 1 : 0, n);
        return out;
    }

    static byte[] scalar(int v) {
        byte[] d = new byte[32];
        d[31] = (byte) v;
        return d;
    }

    static byte[] scalarNm1() {
        return hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550");
    }

    static void failRest() {
        System.out.println("SIGN FAIL");
        System.out.println("PUBKEY FAIL");
        System.out.println("VERIFY_GO_ON_ANDROID FAIL");
        System.out.println("PUBCHECK_GO_ON_ANDROID FAIL");
    }

    static String read(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
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

import java.math.BigInteger;

/**
 * P-256 公钥：d·G，Jacobian 坐标、按位 Montgomery 阶梯。
 * 每一位都做一次倍点与一次加点，比特只决定操作数顺序（条件交换），步骤相同。
 * 运算用 java.math.BigInteger，与 Kotlin 在 JVM / Android 上调用的是同一个类。
 */
public final class P256 {
    static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    static final BigInteger A = P.subtract(BigInteger.valueOf(3));
    static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    static final BigInteger N = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    static final BigInteger GX = new BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    static final BigInteger GY = new BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);

    static final class Jac {
        final BigInteger x, y, z;

        Jac(BigInteger x, BigInteger y, BigInteger z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean inf() {
            return z.signum() == 0;
        }
    }

    private P256() {}

    static BigInteger mod(BigInteger v) {
        return v.mod(P);
    }

    static Jac infinity() {
        return new Jac(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);
    }

    static Jac generator() {
        return new Jac(GX, GY, BigInteger.ONE);
    }

    /** 倍点。a = −3，所以斜率分子是 3(X − Z²)(X + Z²)。 */
    static Jac dbl(Jac p) {
        if (p.inf()) {
            return infinity();
        }
        BigInteger delta = mod(p.z.multiply(p.z));
        BigInteger gamma = mod(p.y.multiply(p.y));
        BigInteger beta = mod(p.x.multiply(gamma));
        BigInteger alpha = mod(p.x.subtract(delta).multiply(p.x.add(delta)).multiply(BigInteger.valueOf(3)));
        BigInteger x3 = mod(alpha.multiply(alpha).subtract(beta.shiftLeft(3)));
        BigInteger z3 = mod(p.y.add(p.z).multiply(p.y.add(p.z)).subtract(gamma).subtract(delta));
        BigInteger y3 = mod(alpha.multiply(beta.shiftLeft(2).subtract(x3)).subtract(gamma.multiply(gamma).shiftLeft(3)));
        return new Jac(x3, y3, z3);
    }

    /** Jacobian 加点。Z = 0 视为无穷远点。 */
    static Jac add(Jac p, Jac q) {
        if (p.inf()) {
            return q;
        }
        if (q.inf()) {
            return p;
        }
        BigInteger z1z1 = mod(p.z.multiply(p.z));
        BigInteger z2z2 = mod(q.z.multiply(q.z));
        BigInteger u1 = mod(p.x.multiply(z2z2));
        BigInteger u2 = mod(q.x.multiply(z1z1));
        BigInteger s1 = mod(p.y.multiply(q.z).multiply(z2z2));
        BigInteger s2 = mod(q.y.multiply(p.z).multiply(z1z1));
        BigInteger h = mod(u2.subtract(u1));
        BigInteger r = mod(s2.subtract(s1));
        if (h.signum() == 0) {
            if (r.signum() == 0) {
                return dbl(p);
            }
            return infinity();
        }
        BigInteger hh = mod(h.multiply(h));
        BigInteger hhh = mod(h.multiply(hh));
        BigInteger v = mod(u1.multiply(hh));
        BigInteger x3 = mod(r.multiply(r).subtract(hhh).subtract(v.shiftLeft(1)));
        BigInteger y3 = mod(r.multiply(v.subtract(x3)).subtract(s1.multiply(hhh)));
        BigInteger z3 = mod(p.z.multiply(q.z).multiply(h));
        return new Jac(x3, y3, z3);
    }

    static int bit(byte[] d, int i) {
        return (d[31 - (i / 8)] >> (i % 8)) & 1;
    }

    /** 从最高位到最低位。比特为 1 时先交换，使倍点与加点的调用顺序与比特无关。 */
    static Jac ladder(byte[] d) {
        if (d.length != 32) {
            throw new IllegalArgumentException("d");
        }
        Jac r0 = infinity();
        Jac r1 = generator();
        for (int i = 255; i >= 0; i--) {
            int bit = bit(d, i);
            Jac a = bit == 0 ? r0 : r1;
            Jac b = bit == 0 ? r1 : r0;
            Jac doubled = dbl(a);
            Jac added = add(a, b);
            r0 = bit == 0 ? doubled : added;
            r1 = bit == 0 ? added : doubled;
        }
        return r0;
    }

    static boolean onCurve(BigInteger x, BigInteger y) {
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(P) >= 0 || y.compareTo(P) >= 0) {
            return false;
        }
        BigInteger left = mod(y.multiply(y));
        BigInteger right = mod(x.multiply(x).multiply(x).add(A.multiply(x)).add(B));
        return left.equals(right);
    }

    static byte[] to32(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == 33 && raw[0] == 0) {
            byte[] out = new byte[32];
            System.arraycopy(raw, 1, out, 0, 32);
            return out;
        }
        if (raw.length > 32) {
            throw new IllegalStateException("coord");
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    /** 未压缩 X9.63：0x04 ‖ X ‖ Y。不在曲线上或无穷远点则抛错。 */
    public static byte[] publicKey(byte[] d32) {
        Jac p = ladder(d32);
        if (p.inf()) {
            throw new IllegalStateException("infinity");
        }
        BigInteger zinv = p.z.modInverse(P);
        BigInteger zinv2 = mod(zinv.multiply(zinv));
        BigInteger x = mod(p.x.multiply(zinv2));
        BigInteger y = mod(p.y.multiply(zinv2).multiply(zinv));
        if (!onCurve(x, y)) {
            throw new IllegalStateException("not on curve");
        }
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(to32(x), 0, out, 1, 32);
        System.arraycopy(to32(y), 0, out, 33, 32);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            s.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            s.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return s.toString();
    }

    static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 与双加对照，并核对 d=1、d=2、d=n−1 和一条 HKDF 样本的已知公钥。 */
    public static void main(String[] args) {
        String[][] known = new String[][]{
            {"0000000000000000000000000000000000000000000000000000000000000001",
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"},
            {"0000000000000000000000000000000000000000000000000000000000000002",
                "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"},
            {"ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550",
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a"},
            {"5f29b5ea117fdc29a695237548e0b52627c01e5cb220c0afe6ce7ec41bd7281f",
                "0403b9337f612dc01b847964e1b35657839e2b118eda5cdd6f46d7fe34783457b97d86df3835d904ab7e070488769392163826335dc1e407f256b2b179a1943d52"},
        };
        for (int i = 0; i < known.length; i++) {
            byte[] d = unhex(known[i][0]);
            String got = hex(publicKey(d));
            if (!got.equals(known[i][1])) {
                throw new RuntimeException("known " + i + " " + got);
            }
            Jac slow = infinity();
            Jac g = generator();
            for (int bit = 255; bit >= 0; bit--) {
                slow = dbl(slow);
                if (bit(d, bit) == 1) {
                    slow = add(slow, g);
                }
            }
            if (!hex(encodeJac(slow)).equals(got)) {
                throw new RuntimeException("ladder/double-add " + i);
            }
        }
        System.out.println("P256 SELF PASS");
    }

    static byte[] encodeJac(Jac p) {
        if (p.inf()) {
            throw new IllegalStateException("infinity");
        }
        BigInteger zinv = p.z.modInverse(P);
        BigInteger zinv2 = mod(zinv.multiply(zinv));
        BigInteger x = mod(p.x.multiply(zinv2));
        BigInteger y = mod(p.y.multiply(zinv2).multiply(zinv));
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(to32(x), 0, out, 1, 32);
        System.arraycopy(to32(y), 0, out, 33, 32);
        return out;
    }
}

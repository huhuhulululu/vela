import Foundation

/// 从 Beta 分布取样（汤普森采样用）。Beta(α, β) = X / (X + Y)，X ~ Gamma(α)，Y ~ Gamma(β)。
/// 参数下限钳到 1（本应用的先验是 1，证据只增不减），因此只需 Marsaglia-Tsang 的 shape ≥ 1 分支。
public enum BetaSampler {
    public static func sample<G: RandomNumberGenerator>(alpha: Double, beta: Double, using rng: inout G) -> Double {
        let x = gamma(max(alpha, 1), using: &rng)
        let y = gamma(max(beta, 1), using: &rng)
        return x / (x + y)
    }

    static func gamma<G: RandomNumberGenerator>(_ shape: Double, using rng: inout G) -> Double {
        let d = shape - 1.0 / 3
        let c = 1 / (9 * d).squareRoot()
        while true {
            let x = normal(using: &rng)
            let t = 1 + c * x
            guard t > 0 else { continue }
            let v = t * t * t
            let u = Double.random(in: 0..<1, using: &rng)
            if u == 0 || log(u) < 0.5 * x * x + d - d * v + d * log(v) { return d * v }
        }
    }

    /// Box-Muller。
    static func normal<G: RandomNumberGenerator>(using rng: inout G) -> Double {
        let u1 = 1 - Double.random(in: 0..<1, using: &rng)
        let u2 = Double.random(in: 0..<1, using: &rng)
        return (-2 * log(u1)).squareRoot() * cos(2 * Double.pi * u2)
    }
}

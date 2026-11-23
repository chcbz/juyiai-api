package cn.jia.agent.service;

/** Encodes one authorized, normalized input. Scope, caching and transactions belong to the caller. */
public interface TaskFeatureEncoder {
    record Identity(String providerConfigVersion, String model, int dimensions) { }
    Identity identity();
    Vector encode(String text);

    /** Response data is private; no raw array escapes to mutate a cached feature. */
    final class Vector {
        private final Identity identity;
        private final float[] values;
        public Vector(Identity identity, float[] values) {
            if (values == null || values.length != identity.dimensions()) throw new InvalidResult();
            this.values = values.clone();
            boolean nonzero = false;
            for (float value : this.values) {
                if (!Float.isFinite(value)) throw new InvalidResult();
                nonzero |= value != 0;
            }
            if (!nonzero) throw new InvalidResult(); // cosine is undefined
            this.identity = identity;
        }
        public Identity identity() { return identity; }
        public float[] values() { return values.clone(); }
    }
    final class InvalidResult extends RuntimeException { }
    final class Unavailable extends RuntimeException { }
}

package dev.zdict.core;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;

import java.io.Closeable;

/**
 * In-memory representation of a dictionary version with cached compression/decompression contexts.
 */
public class DictionaryVersion implements Closeable {

    private final DictionaryMetadata metadata;
    private final byte[] dictBytes;
    private volatile ZstdDictCompress cdict;
    private final ZstdDictDecompress ddict;

    public DictionaryVersion(DictionaryMetadata metadata, byte[] dictBytes, ZstdDictCompress cdict, ZstdDictDecompress ddict) {
        this.metadata = metadata;
        this.dictBytes = dictBytes;
        this.cdict = cdict;
        this.ddict = ddict;
    }

    public static DictionaryVersion createActive(DictionaryMetadata metadata, byte[] bytes, int level) {
        ZstdDictCompress c = new ZstdDictCompress(bytes, level);
        ZstdDictDecompress d = new ZstdDictDecompress(bytes);
        return new DictionaryVersion(metadata, bytes, c, d);
    }

    public static DictionaryVersion createCanary(DictionaryMetadata metadata, byte[] bytes, int level) {
        ZstdDictCompress c = new ZstdDictCompress(bytes, level);
        ZstdDictDecompress d = new ZstdDictDecompress(bytes);
        return new DictionaryVersion(metadata, bytes, c, d);
    }

    public static DictionaryVersion createRetired(DictionaryMetadata metadata, byte[] bytes) {
        ZstdDictDecompress d = new ZstdDictDecompress(bytes);
        return new DictionaryVersion(metadata, bytes, null, d);
    }

    public DictionaryMetadata getMetadata() {
        return metadata;
    }

    public String getId() {
        return metadata.getId();
    }

    public String getGroup() {
        return metadata.getGroup();
    }

    public String getState() {
        return metadata.getState();
    }

    public boolean isActive() {
        return metadata.isActive();
    }

    public boolean isCanary() {
        return metadata.isCanary();
    }

    public boolean isRetired() {
        return metadata.isRetired();
    }

    public byte[] getDictBytes() {
        return dictBytes;
    }

    public ZstdDictCompress getCdict() {
        return cdict;
    }

    public ZstdDictDecompress getDdict() {
        return ddict;
    }

    public synchronized void promoteToActive() {
        metadata.setState("ACTIVE");
    }

    public synchronized void retire() {
        retire(null);
    }

    public synchronized void retire(String reason) {
        metadata.setState("RETIRED");
        if (reason != null) {
            metadata.setRejectionReason(reason);
        }
        if (cdict != null) {
            try {
                cdict.close();
            } catch (Exception ignored) {
            }
            cdict = null;
        }
    }

    @Override
    public synchronized void close() {
        if (cdict != null) {
            try {
                cdict.close();
            } catch (Exception ignored) {
            }
            cdict = null;
        }
        if (ddict != null) {
            try {
                ddict.close();
            } catch (Exception ignored) {
            }
        }
    }
}

package com.mangocraft.plugins.bandwidthsaverpe;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.LongAdder;

public class PacketInfo implements Comparable<PacketInfo>{
    private LongAdder pktCounter;
    private LongAdder pktSize;

    public PacketInfo(){
        this.pktCounter = new LongAdder();
        this.pktSize = new LongAdder();
    }

    public LongAdder getPktCounter() {
        return pktCounter;
    }

    public LongAdder getPktSize() {
        return pktSize;
    }

    /**
     * Add values to both counters atomically for high concurrency performance
     * @param count number of packets to add
     * @param size size of packets to add
     */
    public void addValues(long count, long size) {
        this.pktCounter.add(count);
        this.pktSize.add(size);
    }

    @Override
    public int compareTo(@NotNull PacketInfo o) {
        return Long.compare(this.pktSize.longValue(), o.pktSize.longValue());
    }
}

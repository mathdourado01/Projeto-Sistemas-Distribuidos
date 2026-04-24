package bbs;

public class LogicalClock {
    private long value = 0;

    public long tick() {
        value++;
        return value;
    }

    public long update(long receivedValue) {
        value = Math.max(value, receivedValue);
        return value;
    }

    public long getValue() {
        return value;
    }
}
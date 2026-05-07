package bbs;

public class LogicalClock {
    private long value = 0;

    public synchronized long tick() {
        value++;
        return value;
    }

    public synchronized long update(long receivedValue) {
        value = Math.max(value, receivedValue);
        return value;
    }

    public synchronized long getValue() {
        return value;
    }
}
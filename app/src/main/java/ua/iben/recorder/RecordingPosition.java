package ua.iben.recorder;

/** One atomic pair: a split must never combine the previous file ID with the new offset. */
final class RecordingPosition {
    static final class Moment {
        final String id;
        final long millis;
        Moment(String id,long millis) { this.id=id;this.millis=millis; }
    }
    private String id;
    private long millis;
    synchronized void update(String id,long millis) { this.id=id;this.millis=Math.max(0,millis); }
    synchronized Moment snapshot() { return id==null ? null : new Moment(id,millis); }
    synchronized void clear() { id=null;millis=0; }
}

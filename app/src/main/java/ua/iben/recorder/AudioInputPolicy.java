package ua.iben.recorder;

final class AudioInputPolicy {
    static boolean bluetooth(String key) {return key!=null && key.startsWith("bt:");}
    static int sampleRate(int sdk,String key,int configured) {return bluetooth(key) ? sdk<31 ? 8000 : 16000 : configured;}
    static int bitrate(String key,int configured) {return bluetooth(key) ? Math.min(64,configured) : configured;}
}

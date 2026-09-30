package ua.iben.recorder;
import java.io.IOException;
final class StorageFullException extends IOException {
    StorageFullException(String message){super(message);}
}

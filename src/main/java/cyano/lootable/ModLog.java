package cyano.lootable;
import org.apache.logging.log4j.*;
/** Keeps the existing messages while using Forge25's normal logger. */
public final class ModLog {
    private static final Logger LOG=LogManager.getLogger("lootablebodies");
    private ModLog() { }
    public static void info(String message,Object... args){LOG.info(String.format(java.util.Locale.ROOT,message,args));}
    public static void fine(String message,Object... args){LOG.debug(String.format(java.util.Locale.ROOT,message,args));}
    public static void warning(String message,Object... args){LOG.warn(String.format(java.util.Locale.ROOT,message,args));}
    public static void log(Level level,Throwable error,String message){LOG.log(level,message,error);}
}

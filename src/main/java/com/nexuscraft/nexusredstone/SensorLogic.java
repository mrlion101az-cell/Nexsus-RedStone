package com.nexuscraft.nexusredstone;

/**
 * Turns a sensor's raw reading (a count of entities, a light level, a weather code, the world
 * time, a container fill in permille) into a 0-15 signal strength according to the sensor's
 * settings. Taking the raw number as a plain int is what keeps this entire layer testable without
 * a server: the Bukkit-facing host only has to produce the raw reading.
 */
final class SensorLogic {

    private SensorLogic() {
    }

    /** Raw weather codes the host reports. */
    static final int WEATHER_CLEAR = 0;
    static final int WEATHER_RAIN = 1;
    static final int WEATHER_THUNDER = 2;

    static int signal(Component c, int raw) {
        switch (c.type) {
            case SENSOR_PLAYER:
            case SENSOR_MOB:
            case SENSOR_ITEM:
                if (c.param("analog") == 1) {
                    return Math.max(0, Math.min(15, raw));
                }
                return raw > 0 ? 15 : 0;
            case SENSOR_LIGHT:
                if (c.param("analog") == 1) {
                    return Math.max(0, Math.min(15, raw));
                }
                return raw >= c.param("level") ? 15 : 0;
            case SENSOR_WEATHER:
                if (c.param("analog") == 1) {
                    return raw >= WEATHER_THUNDER ? 15 : (raw == WEATHER_RAIN ? 8 : 0);
                }
                return raw > WEATHER_CLEAR ? 15 : 0;
            case SENSOR_TIME:
                return timeSignal(c.param("mode"), raw);
            case SENSOR_FILL:
                if (c.param("analog") == 1) {
                    return fillSignal(raw);
                }
                return raw > 0 ? 15 : 0;
            default:
                return 0;
        }
    }

    /** Vanilla's own comparator formula for a container: 0 only when truly empty, otherwise at
     *  least 1, rising to 15 when full. {@code permille} is fullness in thousandths. */
    static int fillSignal(int permille) {
        if (permille <= 0) {
            return 0;
        }
        int level = (int) Math.floor(Math.min(1000, permille) * 14.0 / 1000.0) + 1;
        return Math.min(15, level);
    }

    /** Time of day: mode 0 is 15 during the day, mode 1 is 15 at night, mode 2 is an analog sun
     *  height (15 at noon, 0 from sunset until sunrise), like a daylight detector. */
    static int timeSignal(int mode, long worldTime) {
        long t = ((worldTime % 24000L) + 24000L) % 24000L;
        boolean day = t < 13000L;
        switch (mode) {
            case 0:
                return day ? 15 : 0;
            case 1:
                return day ? 0 : 15;
            default:
                double angle = (t - 6000L) / 24000.0 * 2.0 * Math.PI;
                double height = Math.cos(angle);
                return (int) Math.round(Math.max(0.0, height) * 15.0);
        }
    }
}

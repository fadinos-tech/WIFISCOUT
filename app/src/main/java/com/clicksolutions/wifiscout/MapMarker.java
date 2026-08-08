package com.clicksolutions.wifiscout;

public class MapMarker {

    public enum Type {
        ROUTER, EXTENDER, KITCHEN, LIVING_ROOM, BEDROOM, BATHROOM,
        HALLWAY, OFFICE, GARAGE, STAIRS, FLOOR_CHANGE, CUSTOM
    }

    public float  worldX;
    public float  worldY;
    public Type   type;
    public String label;

    public MapMarker(float worldX, float worldY, Type type, String label) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.type   = type;
        this.label  = label;
    }

    public static String defaultLabel(Type type) {
        switch (type) {
            case ROUTER:       return "Router";
            case EXTENDER:     return "Extender";
            case KITCHEN:      return "Kitchen";
            case LIVING_ROOM:  return "Living Room";
            case BEDROOM:      return "Bedroom";
            case BATHROOM:     return "Bathroom";
            case HALLWAY:      return "Hallway";
            case OFFICE:       return "Office";
            case GARAGE:       return "Garage";
            case STAIRS:       return "Stairs";
            case FLOOR_CHANGE: return "Floor Change";
            default:           return "Custom";
        }
    }

    public int iconColor() {
        switch (type) {
            case ROUTER:       return 0xFF1565C0;
            case EXTENDER:     return 0xFF6A1B9A;
            case KITCHEN:      return 0xFF2E7D32;
            case LIVING_ROOM:  return 0xFF00838F;
            case BEDROOM:      return 0xFF4527A0;
            case BATHROOM:     return 0xFF0277BD;
            case HALLWAY:      return 0xFF558B2F;
            case OFFICE:       return 0xFF6D4C41;
            case GARAGE:       return 0xFF37474F;
            case STAIRS:       return 0xFFE65100;
            case FLOOR_CHANGE: return 0xFFE65100;
            default:           return 0xFF455A64;
        }
    }

    public String iconText() {
        switch (type) {
            case ROUTER:       return "R";
            case EXTENDER:     return "E";
            case KITCHEN:      return "K";
            case LIVING_ROOM:  return "L";
            case BEDROOM:      return "B";
            case BATHROOM:     return "WC";
            case HALLWAY:      return "H";
            case OFFICE:       return "O";
            case GARAGE:       return "G";
            case STAIRS:       return "S";
            case FLOOR_CHANGE: return "F";
            default:           return "?";
        }
    }
}

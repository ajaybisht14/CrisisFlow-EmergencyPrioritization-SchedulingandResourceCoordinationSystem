package com.crisisflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NotificationService {
    private NotificationService() {}
    public static void create(Integer emergencyId,Integer resourceId,String event,String message) throws Exception {
        try(Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement("INSERT INTO notifications (emergency_id,resource_id,event_type,message,dedupe_key) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE notification_id=notification_id")) {
            p.setObject(1,emergencyId); p.setObject(2,resourceId); p.setString(3,event); p.setString(4,message);
            p.setString(5,event+":"+(emergencyId==null?"-":emergencyId)+":"+(resourceId==null?"-":resourceId)); p.executeUpdate();
        }
    }
    public static List<Map<String,Object>> list() throws Exception {
        List<Map<String,Object>> out=new ArrayList<>();
        try(Connection c=Database.getConnection(); PreparedStatement p=c.prepareStatement("SELECT notification_id,emergency_id,resource_id,event_type,message,is_read,created_at FROM notifications ORDER BY created_at DESC LIMIT 200"); ResultSet r=p.executeQuery()) {
            while(r.next()){ Map<String,Object> m=new LinkedHashMap<>(); m.put("notificationId",r.getInt(1)); m.put("emergencyId",r.getObject(2)); m.put("resourceId",r.getObject(3)); m.put("eventType",r.getString(4)); m.put("message",r.getString(5)); m.put("isRead",r.getBoolean(6)); m.put("createdAt",r.getTimestamp(7).toInstant().toString()); out.add(m); }
        } return out;
    }
}

package ai.teraq.roadguardian;

import java.util.List;

/**
 * 화면 이동용 식별자만 가진다. 기록을 저장하거나 권한을 주지 않는다
 */
public final class VehicleOverviewSelection {
    private String category = "all";
    private String eventId;

    public String getCategory() {
        return category;
    }

    public String getEventId() {
        return eventId;
    }

    public void clear() {
        category = "all";
        eventId = null;
    }

    public void setCategory(String next) {
        String checked = "road".equals(next) || "maintenance".equals(next) || "incident".equals(next) || "help".equals(next) ? next : "all";
        if (!category.equals(checked)) {
            category = checked;
            eventId = null;
        }
    }

    public void select(String id) {
        eventId = id;
    }

    public int resolve(List<String> visibleIds) {
        int position = eventId == null ? -1 : visibleIds.indexOf(eventId);
        if (position < 0) position = visibleIds.isEmpty() ? -1 : 0;
        eventId = position < 0 ? null : visibleIds.get(position);
        return position;
    }
}

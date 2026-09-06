package com.apparat.model;

/** Mirrors one row of quota_usage. Read-only snapshot for display — the actual consumption is a single atomic SQL UPDATE in dao.QuotaDao#consume, never a read-modify-write through this object (see ARCHITECTURE_DECISIONS.md ADR-11). */
public class QuotaUsage {
    private Long userId;
    private int isoYear;
    private int isoWeek;
    private int usedMinutes;
    private int limitMinutes;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getIsoYear() { return isoYear; }
    public void setIsoYear(int isoYear) { this.isoYear = isoYear; }
    public int getIsoWeek() { return isoWeek; }
    public void setIsoWeek(int isoWeek) { this.isoWeek = isoWeek; }
    public int getUsedMinutes() { return usedMinutes; }
    public void setUsedMinutes(int usedMinutes) { this.usedMinutes = usedMinutes; }
    public int getLimitMinutes() { return limitMinutes; }
    public void setLimitMinutes(int limitMinutes) { this.limitMinutes = limitMinutes; }
    public int getRemainingMinutes() { return Math.max(0, limitMinutes - usedMinutes); }
}

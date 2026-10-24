package club.champion.tennis;

import java.time.*;
import java.util.*;

/** Personal attendance totals over one snapshot; a past date alone is not a visit. */
public final class AttendanceStats {
    public int total, lastYearTotal, recentTotal;
    public final int[] recentVisits = new int[7], months = new int[12];
    public final LocalDate[] recentDays = new LocalDate[7];
    public final YearMonth[] monthDates = new YearMonth[12];
    private AttendanceStats(){}
    public static AttendanceStats calculate(List<UserRecord> records,Instant now){
        AttendanceStats stats=new AttendanceStats();
        LocalDate today=now.atZone(YclientsApi.CLUB_ZONE).toLocalDate();
        YearMonth currentMonth=YearMonth.from(today);
        for(int i=0;i<12;i++)stats.monthDates[i]=currentMonth.minusMonths(11-i);
        for(int i=0;i<7;i++)stats.recentDays[i]=today.minusDays(6-i);
        Map<Long,UserRecord> unique=new LinkedHashMap<>();
        for(UserRecord record:records)unique.put(record.id,record);
        for(UserRecord record:unique.values()){
            ZonedDateTime local=record.date.withZoneSameInstant(YclientsApi.CLUB_ZONE);
            if(record.deleted||record.attendance!=1||local.plusMinutes(record.minutes).toInstant().isAfter(now))continue;
            stats.total++;
            long month=java.time.temporal.ChronoUnit.MONTHS.between(stats.monthDates[0],YearMonth.from(local));
            if(month>=0&&month<12){stats.lastYearTotal++;stats.months[(int)month]++;}
            long day=java.time.temporal.ChronoUnit.DAYS.between(stats.recentDays[0],local.toLocalDate());
            if(day>=0&&day<7){stats.recentVisits[(int)day]++;stats.recentTotal++;}
        }
        return stats;
    }
}

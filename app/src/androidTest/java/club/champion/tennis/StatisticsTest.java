package club.champion.tennis;

import android.test.InstrumentationTestCase;
import org.json.*;
import java.time.*;
import java.util.*;

public class StatisticsTest extends InstrumentationTestCase {
    public void testAttendanceRulesCalendarAndDuplicateUpdates()throws Exception{
        JSONArray items=new JSONArray();
        items.put(record(1,"2026-01-05 17:00:00",1,false));
        items.put(record(2,"2026-01-07 17:00:00",1,false));
        items.put(record(3,"2026-05-10 17:00:00",1,false));
        items.put(record(4,"2025-12-31 14:00:00",1,false));
        items.put(record(5,"2026-01-08 17:00:00",1,true));
        items.put(record(6,"2026-01-09 17:00:00",-1,false));
        items.put(record(7,"2026-01-10 17:00:00",0,false));
        items.put(record(8,"2026-01-11 17:00:00",2,false));
        items.put(record(9,"2026-07-01 17:00:00",1,false));
        items.put(record(10,"2026-06-15 16:00:00",1,false));
        items.put(record(11,"2026-06-15 15:00:00",1,false));
        JSONObject utc=record(12,"2026-01-01 01:30:00",1,false);utc.remove("date");utc.put("datetime","2025-12-31T21:30:00Z");items.put(utc);
        JSONObject elsewhere=record(13,"2026-01-02 17:00:00",1,false);elsewhere.getJSONObject("company").put("id",999);items.put(elsewhere);
        List<UserRecord> records=UserRecord.parse(new JSONObject().put("success",true).put("data",items));
        records.add(new UserRecord(record(1,"2026-01-05 17:00:00",1,false)));
        records.add(new UserRecord(record(14,"2026-01-03 17:00:00",1,false)));
        records.add(new UserRecord(record(14,"2026-01-03 17:00:00",1,true)));
        Instant now=Instant.parse("2026-06-15T12:00:00Z");
        AttendanceStats stats=AttendanceStats.calculate(records,now);
        assertEquals(6,stats.total);assertEquals(6,stats.lastYearTotal);
        assertTrue(Arrays.equals(new int[]{0,0,0,0,0,1,3,0,0,0,1,1},stats.months));
        assertEquals(1,stats.recentTotal);assertTrue(Arrays.equals(new int[]{0,0,0,0,0,0,1},stats.recentVisits));
    }
    public void testEmptyStatisticsHaveZeroBars()throws Exception{
        AttendanceStats stats=AttendanceStats.calculate(Collections.emptyList(),Instant.parse("2026-06-15T12:00:00Z"));
        assertEquals(0,stats.total);assertEquals(0,stats.lastYearTotal);assertEquals(12,stats.months.length);assertEquals(7,stats.recentVisits.length);assertEquals(0,stats.recentTotal);
        for(int value:stats.months)assertEquals(0,value);for(int value:stats.recentVisits)assertEquals(0,value);
    }
    public void testLastSevenDaysCrossYear()throws Exception{
        List<UserRecord> records=new ArrayList<>();
        records.add(new UserRecord(record(21,"2025-12-27 17:00:00",1,false)));
        records.add(new UserRecord(record(22,"2025-12-28 17:00:00",1,false)));
        records.add(new UserRecord(record(23,"2025-12-31 17:00:00",1,false)));
        records.add(new UserRecord(record(24,"2026-01-01 17:00:00",1,false)));
        records.add(new UserRecord(record(25,"2026-01-03 11:00:00",1,false)));
        records.add(new UserRecord(record(26,"2026-01-03 14:00:00",1,false)));
        records.add(new UserRecord(record(27,"2026-01-04 11:00:00",1,false)));
        records.add(new UserRecord(record(28,"2025-12-29 17:00:00",1,true)));
        records.add(new UserRecord(record(29,"2025-12-30 17:00:00",0,false)));
        Instant now=Instant.parse("2026-01-03T10:00:00Z");
        AttendanceStats stats=AttendanceStats.calculate(records,now);
        assertEquals(LocalDate.of(2025,12,28),stats.recentDays[0]);assertEquals(LocalDate.of(2026,1,3),stats.recentDays[6]);
        assertEquals(5,stats.total);assertEquals(5,stats.lastYearTotal);assertEquals(4,stats.recentTotal);
        assertTrue(Arrays.equals(new int[]{1,0,0,1,1,0,1},stats.recentVisits));
    }
    public void testLastTwelveCalendarMonthsAcrossYearBoundary()throws Exception{
        List<UserRecord> records=new ArrayList<>();
        records.add(new UserRecord(record(31,"2025-01-31 17:00:00",1,false)));
        records.add(new UserRecord(record(32,"2025-02-01 17:00:00",1,false)));
        records.add(new UserRecord(record(33,"2025-12-31 17:00:00",1,false)));
        records.add(new UserRecord(record(34,"2026-01-01 17:00:00",1,false)));
        records.add(new UserRecord(record(35,"2026-01-03 13:30:00",1,false)));
        records.add(new UserRecord(record(36,"2026-02-01 11:00:00",1,false)));
        records.add(new UserRecord(record(37,"2025-02-02 17:00:00",1,true)));
        AttendanceStats stats=AttendanceStats.calculate(records,Instant.parse("2026-01-03T10:00:00Z"));
        assertEquals(YearMonth.of(2025,2),stats.monthDates[0]);assertEquals(YearMonth.of(2026,1),stats.monthDates[11]);
        assertEquals(4,stats.total);assertEquals(3,stats.lastYearTotal);
        assertTrue(Arrays.equals(new int[]{1,0,0,0,0,0,0,0,0,0,1,1},stats.months));
        for(int i=1;i<12;i++)assertEquals(stats.monthDates[i-1].plusMonths(1),stats.monthDates[i]);
    }
    JSONObject record(long id,String date,int attendance,boolean deleted)throws Exception{
        return new JSONObject().put("id",id).put("company",new JSONObject().put("id",1538880)).put("services",new JSONArray().put(new JSONObject().put("title","Теннис")))
            .put("date",date).put("length",3600).put("visit_attendance",attendance).put("deleted",deleted);
    }
}

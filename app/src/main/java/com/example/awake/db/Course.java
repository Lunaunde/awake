package com.example.awake.db;

public class Course {
    private long id;
    private String name;
    private String teacher;
    private String room;
    private String weekConfig; // 例如 "1-16" 或 "1,3,5"
    private int day;          // 1=周一 ... 7=周日
    private int start;        // 开始节次
    private int end;          // 结束节次
    private int color;

    // 构造方法（全参）
    public Course(long id, String name, String teacher, String room,
                  String weekConfig, int day, int start, int end, int color) {
        this.id = id;
        this.name = name;
        this.teacher = teacher;
        this.room = room;
        this.weekConfig = weekConfig;
        this.day = day;
        this.start = start;
        this.end = end;
        this.color = color;
    }

    // 为了插入方便，再提供一个无id的构造（id由数据库自增）
    public Course(String name, String teacher, String room,
                  String weekConfig, int day, int start, int end, int color) {
        this(0, name, teacher, room, weekConfig, day, start, end, color);
    }

    // ----- 生成所有 Getter 和 Setter (必须要有) -----
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTeacher() { return teacher; }
    public void setTeacher(String teacher) { this.teacher = teacher; }
    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }
    public String getWeekConfig() { return weekConfig; }
    public void setWeekConfig(String weekConfig) { this.weekConfig = weekConfig; }
    public int getDay() { return day; }
    public void setDay(int day) { this.day = day; }
    public int getStart() { return start; }
    public void setStart(int start) { this.start = start; }
    public int getEnd() { return end; }
    public void setEnd(int end) { this.end = end; }
    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }
}
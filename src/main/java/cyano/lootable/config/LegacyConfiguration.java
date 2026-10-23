package cyano.lootable.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Reads the existing Forge CFG choices without converting the user's file. */
public final class LegacyConfiguration {
    private static final Pattern CATEGORY=Pattern.compile("^\\s*(?:\"([^\"]+)\"|([\\w.-]+))\\s*\\{\\s*(?:#.*)?$");
    private static final Pattern VALUE=Pattern.compile("^(\\s*)([BIDS]):(?:\"([^\"]+)\"|([^=\\s]+))\\s*=(.*)$");
    private final File file;
    private final Map<String,Category> categories=new TreeMap<>();
    private final Map<String,Integer> closes=new HashMap<>();
    private List<String> lines=new ArrayList<>();

    public LegacyConfiguration(File file){this.file=file;}
    public void load() {
        categories.clear();closes.clear();
        try {lines=file.isFile()?Files.readAllLines(file.toPath(),StandardCharsets.UTF_8):new ArrayList<>();}
        catch(IOException failure){throw new IllegalStateException("Could not read Lootable Bodies settings",failure);}
        Deque<String> path=new ArrayDeque<>();
        boolean list=false;
        for(int i=0;i<lines.size();i++) {
            String text=lines.get(i),trim=text.trim();
            if(list){if(trim.equals(">"))list=false;continue;}
            if(trim.startsWith("#") || trim.isEmpty())continue;
            Matcher header=CATEGORY.matcher(text);
            if(header.matches()){path.addLast(header.group(1)!=null?header.group(1):header.group(2));getCategory(String.join(".",path));continue;}
            if(trim.equals("}")){if(!path.isEmpty()){closes.put(String.join(".",path),i);path.removeLast();}continue;}
            if(trim.matches("[BIDS]:.*<\\s*")){list=true;continue;}
            Matcher value=VALUE.matcher(text);
            if(!path.isEmpty() && value.matches()) {
                String name=value.group(3)!=null?value.group(3):value.group(4);
                Property property=new Property(name,value.group(2).charAt(0),value.group(5).trim());
                property.line=i;property.indent=value.group(1);getCategory(String.join(".",path)).put(name,property);
            }
        }
    }
    public Set<String> getCategoryNames(){return Collections.unmodifiableSet(categories.keySet());}
    public Category getCategory(String name){return categories.computeIfAbsent(name,ignored->new Category());}
    private Property get(String category,String name,char type,String value,String comment) {
        Property property=getCategory(category).get(name);
        if(property==null){property=new Property(name,type,value);getCategory(category).put(name,property);}
        property.fallback=value;property.comment=comment;return property;
    }
    public boolean getBoolean(String name,String category,boolean value,String comment){return get(category,name,'B',Boolean.toString(value),comment).getBoolean(value);}
    public String getString(String name,String category,String value,String comment){return get(category,name,'S',value,comment).getString();}
    public float getFloat(String name,String category,float value,float min,float max,String comment) {
        Property property=get(category,name,'D',Float.toString(value),comment);property.min=min;property.max=max;
        return Math.max(min,Math.min(max,(float)property.getDouble(value)));
    }
    /** Updates known values in place; comments, lists and unknown pack settings survive. */
    public void save() {
        Map<Integer,List<String>> insert=new TreeMap<>();Map<Integer,String> replace=new HashMap<>();List<String> tail=new ArrayList<>();
        for(Map.Entry<String,Category> category:categories.entrySet()) {
            List<String> missing=new ArrayList<>();
            for(Property property:category.getValue().values()) {
                if(property.line>=0) {
                    if(property.changed)replace.put(property.line,property.indent+property.type+":"+property.name+"="+property.value);
                } else {
                    if(property.comment!=null)for(String comment:property.comment.split("\\n",-1))missing.add("    # "+comment);
                    missing.add("    "+property.type+":"+property.name+"="+property.value);
                }
            }
            if(missing.isEmpty())continue;
            Integer close=closes.get(category.getKey());
            if(close!=null)insert.computeIfAbsent(close,ignored->new ArrayList<>()).addAll(missing);
            else {tail.add("");tail.add("\""+category.getKey()+"\" {");tail.addAll(missing);tail.add("}");}
        }
        List<String> output=new ArrayList<>();
        for(int i=0;i<lines.size();i++){if(insert.containsKey(i))output.addAll(insert.get(i));output.add(replace.getOrDefault(i,lines.get(i)));}output.addAll(tail);
        try {
            Path destination=file.toPath().toAbsolutePath();Files.createDirectories(destination.getParent());Path temp=Files.createTempFile(destination.getParent(),"lootablebodies-",".cfg.tmp");
            try {
                Files.write(temp,(String.join("\n",output)+"\n").getBytes(StandardCharsets.UTF_8));
                try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
                try{Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);}
            } finally {Files.deleteIfExists(temp);}
            // Retain the GUI's Property objects and metadata while updating their line numbers.
            lines=output;closes.clear();Deque<String> path=new ArrayDeque<>();boolean list=false;
            for(int i=0;i<lines.size();i++) {
                String text=lines.get(i),trim=text.trim();if(list){if(trim.equals(">"))list=false;continue;}
                Matcher header=CATEGORY.matcher(text);
                if(header.matches()){path.addLast(header.group(1)!=null?header.group(1):header.group(2));continue;}
                if(trim.equals("}")){if(!path.isEmpty()){closes.put(String.join(".",path),i);path.removeLast();}continue;}
                if(trim.matches("[BIDS]:.*<\\s*")){list=true;continue;}
                Matcher value=VALUE.matcher(text);if(!path.isEmpty() && value.matches()) {
                    Property property=getCategory(String.join(".",path)).get(value.group(3)!=null?value.group(3):value.group(4));
                    if(property!=null){property.line=i;property.indent=value.group(1);property.changed=false;}
                }
            }
        } catch(IOException failure){throw new IllegalStateException("Could not save Lootable Bodies settings",failure);}
    }
    public static final class Category extends TreeMap<String,Property> {private static final long serialVersionUID=1L;}
    public static final class Property {
        private final String name;private final char type;private String value,fallback,comment,languageKey="",indent="    ";
        private int line=-1;private boolean changed,restart;private double min=-Double.MAX_VALUE,max=Double.MAX_VALUE;
        Property(String name,char type,String value){this.name=name;this.type=type;this.value=value;this.fallback=value;}
        public String getName(){return name;}public String getString(){return value;}public char getType(){return type;}
        public String getDefault(){return fallback;}
        public boolean getBoolean(){return getBoolean(Boolean.parseBoolean(fallback));}
        public boolean getBoolean(boolean defaultValue){return value.equalsIgnoreCase("true")?true:value.equalsIgnoreCase("false")?false:defaultValue;}
        public int getInt(){try{return Integer.parseInt(value);}catch(NumberFormatException invalid){return 0;}}
        public double getDouble(){try{return getDouble(Double.parseDouble(fallback));}catch(NumberFormatException invalid){return 0D;}}
        double getDouble(double defaultValue){try{return Double.parseDouble(value);}catch(NumberFormatException invalid){return defaultValue;}}
        public void set(boolean value){set(Boolean.toString(value));}public void set(double value){set(Double.toString(value));}public void set(int value){set(Integer.toString(value));}
        public void set(String value){this.changed|=!this.value.equals(value);this.value=value;}
        public String getLanguageKey(){return languageKey;}public void setLanguageKey(String key){languageKey=key;}
        public void setRequiresMcRestart(boolean value){restart=value;}public boolean requiresMcRestart(){return restart;}public boolean requiresWorldRestart(){return restart;}
        public double getMin(){return min;}public double getMax(){return max;}
        public void reset(){set(fallback);}
    }
}

package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import cyano.lootable.config.LegacyConfiguration.Property;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.api.distmarker.*;

/** The ordinary Mods-list settings screen, retaining the existing CFG choices. */
@OnlyIn(Dist.CLIENT)
public final class CorpseConfigGui extends Screen {
    private static final String[] APPEARANCE={"local_texture","display_nametag"};
    private static final String[] RULES={"corpse_HP","add_bones_to_corpse","item_decay_rate","hurt_by_environment","hurt_by_weapons","hurt_by_misc","enable_corpse_decay","empty_only_decay","corpse_decay_time","enable_corpse_buoyancy","prevent_void_loss","dim_specific_platform"};
    private final Screen parent;
    private final String group;
    private final Map<String,String> pending;
    private final Map<String,TextFieldWidget> fields=new LinkedHashMap<>();
    private final Map<String,Integer> rows=new LinkedHashMap<>();
    private int page,perPage;
    private boolean invalid;
    public CorpseConfigGui(Screen parent){this(parent,null,new LinkedHashMap<>(),0);for(String key:keys())pending.put(key,property(key).getString());}
    private CorpseConfigGui(Screen parent,String group,Map<String,String> pending,int page){super(new net.minecraft.util.text.StringTextComponent(LootableBodies.NAME));this.parent=parent;this.group=group;this.pending=new LinkedHashMap<>(pending);this.page=page;}
    private static List<String> keys(){List<String> result=new ArrayList<>(Arrays.asList(APPEARANCE));result.addAll(Arrays.asList(RULES));return result;}
    private static Property property(String key){for(String category:new String[]{"options","corpse damage","corpse decay","corpse recovery"}){Property value=LootableBodies.getConfiguration().getCategory(category).get(key);if(value!=null)return value;}throw new IllegalArgumentException(key);}
    private Button button(int id,int x,int y,int width,String label,Runnable click){return addButton(new SettingButton(id,x,y,width,label,click));}
    public static final class SettingButton extends Button {
        public final int id;
        SettingButton(int id,int x,int y,int width,String label,Runnable action){super(x,y,width,20,new net.minecraft.util.text.StringTextComponent(label),button->action.run());this.id=id;}
    }
    @Override protected void init(){
        fields.clear();rows.clear();int left=width/2-155;
        if(group==null){
            button(1,left,65,310,I18n.format("lootablebodies.config.appearance"),()->minecraft.displayGuiScreen(new CorpseConfigGui(this,"appearance",pending,0)));
            button(2,left,95,310,I18n.format("lootablebodies.config.rules"),()->minecraft.displayGuiScreen(new CorpseConfigGui(this,"rules",pending,0))).active=minecraft.world==null;
        }else{
            String[] keys=group.equals("appearance")?APPEARANCE:RULES;perPage=Math.max(2,(height-112)/26);page=Math.min(page,(keys.length-1)/perPage);
            int start=page*perPage,end=Math.min(keys.length,start+perPage);
            for(int i=start;i<end;i++){
                String key=keys[i];Property value=property(key);int y=48+(i-start)*26;rows.put(key,y);
                if(value.getType()=='B')button(20+i,width/2+70,y,85,booleanLabel(key),()->{capture();pending.put(key,Boolean.toString(!Boolean.parseBoolean(pending.get(key))));init(minecraft,width,height);});
                else {TextFieldWidget field=new TextFieldWidget(font,width/2+60,y+1,95,18,new net.minecraft.util.text.TranslationTextComponent(property(key).getLanguageKey()));field.setMaxStringLength(64);field.setText(pending.get(key));fields.put(key,field);children.add(field);}
            }
            if(page>0)button(3,left,height-56,80,"<",()->changePage(-1));
            if(end<keys.length)button(4,width/2+75,height-56,80,">",()->changePage(1));
            button(5,width/2-65,height-56,130,I18n.format("controls.reset"),()->{for(String key:keys)pending.put(key,property(key).getDefault());init(minecraft,width,height);});
        }
        button(200,width/2-155,height-28,150,I18n.format("gui.done"),()->{
            capture();if(!validPending()){invalid=true;return;}
            if(group==null){for(String key:keys())property(key).set(pending.get(key));ClientConfigChanges.apply();}else if(parent instanceof CorpseConfigGui)((CorpseConfigGui)parent).pending.putAll(pending);minecraft.displayGuiScreen(parent);
        });
        button(201,width/2+5,height-28,150,I18n.format("gui.cancel"),()->minecraft.displayGuiScreen(parent));
    }
    private void capture(){for(Map.Entry<String,TextFieldWidget> entry:fields.entrySet())pending.put(entry.getKey(),entry.getValue().getText());}
    private void changePage(int change){capture();page+=change;init(minecraft,width,height);}
    private String booleanLabel(String key){return I18n.format(Boolean.parseBoolean(pending.get(key))?"options.on":"options.off");}
    private boolean validPending(){
        for(String key:keys()){
            Property property=property(key);String value=pending.get(key);
            if(key.equals("corpse_decay_time")){if(!isValidDecayTime(value))return false;}
            else if(property.getType()=='D' || property.getType()=='I')try{double number=Double.parseDouble(value);if(!Double.isFinite(number)||number<property.getMin()||number>property.getMax())return false;}catch(NumberFormatException error){return false;}
        }return true;
    }
    @Override public void tick(){for(TextFieldWidget field:fields.values())field.tick();}
    @Override public void render(com.mojang.blaze3d.matrix.MatrixStack matrices,int mouseX,int mouseY,float partialTicks){
        renderBackground(matrices);drawCenteredString(matrices,font,LootableBodies.NAME,width/2,12,0xffffff);
        drawCenteredString(matrices,font,I18n.format(group==null?"lootablebodies.config.local_file":"lootablebodies.config."+group),width/2,28,0xcccccc);
        for(Map.Entry<String,Integer> row:rows.entrySet()){
            String text=I18n.format(property(row.getKey()).getLanguageKey());drawString(matrices,font,font.trimStringToWidth(text,205),width/2-155,row.getValue()+6,0xffffff);
        }
        super.render(matrices,mouseX,mouseY,partialTicks);for(TextFieldWidget field:fields.values())field.render(matrices,mouseX,mouseY,partialTicks);
        for(Map.Entry<String,Integer> row:rows.entrySet())if(mouseX>=width/2-155 && mouseX<width/2+155 && mouseY>=row.getValue() && mouseY<row.getValue()+22)
            renderTooltip(matrices,font.trimStringToWidth(new net.minecraft.util.text.TranslationTextComponent(property(row.getKey()).getLanguageKey()+".tooltip"),300),mouseX,mouseY);
        if(group==null && mouseX>=width/2-155 && mouseX<width/2+155 && mouseY>=95 && mouseY<115)
            renderTooltip(matrices,font.trimStringToWidth(new net.minecraft.util.text.TranslationTextComponent("lootablebodies.config.rules.tooltip"),300),mouseX,mouseY);
        if(invalid)drawCenteredString(matrices,font,I18n.format("lootablebodies.config.invalid"),width/2,height-72,0xff5555);
    }
    @Override public void closeScreen(){capture();minecraft.displayGuiScreen(parent);}
    public static boolean isValidDecayTime(String value) {
        if (!value.matches("\\s*[0-9]+\\s*:\\s*[0-9]+\\s*(?::\\s*[0-9]+\\s*)?")) return false;
        String[] parts = value.trim().split(":");
        long total = 0;
        int[] multiplier = {3600, 60, 1};
        long limit = Integer.MAX_VALUE / 20;
        try {
            for (int i = 0; i < parts.length; i++) {
                long part = Long.parseLong(parts[i].trim());
                if (part > limit / multiplier[i]) return false;
                total += part * multiplier[i];
            }
            return total <= limit;
        } catch (NumberFormatException invalid) { return false; }
    }

}

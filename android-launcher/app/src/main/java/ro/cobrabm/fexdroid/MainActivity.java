package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

/** TLOU launcher: PKG or extracted game -> automatic on-device preparation. */
public final class MainActivity extends Activity {
    private static final int PICK_GAME=501, PICK_DRIVER=502, PICK_PKG=503, PICK_DEST=504, SAVE_LOGS=505, IMPORT_SAVES=506, EXPORT_SAVES=507,
        PICK_UPDATE_PKG=508, PICK_UPDATE_FOLDER=509;
    private static final int BACKGROUND=0xff101613, PANEL=0xff1b2520, TEXT=0xffe7e9df, MUTED=0xffa6b0a4, ACCENT=0xffc2cf96;
    private SharedPreferences prefs;
    private TextView gameInfo, driverInfo, status, permission;
    private ProgressBar progress;
    private final List<Button> buttons=new ArrayList<>();
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean busy;
    private String currentTab="home";
    private Runnable afterPermission;
    private Uri pendingPkg;
    private InstallTools tools;
    private boolean directLaunchConsumed;
    private boolean firstRunDialogShown;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("tlou",MODE_PRIVATE);
        PortSettings.retireTestPreferences(this);
        // Log 32 actually ran at 720p with six effect-disabling patches.
        // Only set these on a fresh install; never overwrite earlier choices.
        SharedPreferences.Editor initial=prefs.edit();
        if(!prefs.contains("resolution"))initial.putString("resolution","720");
        for(String effect:PortSettings.EFFECTS)
            if(!prefs.contains(effect))
                initial.putBoolean(effect,
                    !effect.equals("omni_light_shadows") && !effect.equals("spot_light_shadows"));
        // Earlier v0.1.1 launches inherited true for EVERY new effect.
        // That accidentally enabled experimental shadow-category patches on
        // first launch. Reset just those two ONCE for existing installations;
        // all six established effect preferences remain untouched.
        if(!prefs.getBoolean("shadow_experimental_defaults_fixed_v1",false)) {
            initial.putBoolean("omni_light_shadows",false);
            initial.putBoolean("spot_light_shadows",false);
            initial.putBoolean("shadow_experimental_defaults_fixed_v1",true);
        }
        initial.apply();
        // Keep paths chosen in the first experimental APK.
        if (!prefs.contains("game_path")) prefs.edit().putString("game_path",getPreferences(0).getString("prepared_path","")).apply();
        if (saved!=null && saved.getString("pending_pkg")!=null) pendingPkg=Uri.parse(saved.getString("pending_pkg"));
        tools=new InstallTools(this);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        buildHome();
        // Storage authorization is the first onboarding interaction, before
        // game selection and before any optional driver setup.
        if(!prefs.getBoolean("onboarding_seen",false)) {
            if(!access())withAccess(this::showFirstRunSetup);
            else showFirstRunSetup();
        } else scheduleDirectLaunch();
        // Optional packaged driver installation runs off the main/UI thread.
        worker.execute(()->{
            try { BuiltinDrivers.ensureDefault(this); }
            catch(Exception e) { android.util.Log.w("TLOU", "Bundled Turnip auto-select unavailable", e); }
            runOnUiThread(()->{if(!isDestroyed())refresh();});
        });
    }
    private void showFirstRunSetup() {
        if(firstRunDialogShown || isFinishing())return;
        firstRunDialogShown=true;
        GameSetupDialog.show(this,selection->{
            prefs.edit().putBoolean("onboarding_seen",true).apply();
            if(selection==0)pickFile(PICK_PKG);
            else if(selection==1)pickFolder(PICK_GAME);
            else scheduleDirectLaunch();
        });
    }

    private void scheduleDirectLaunch() {
        if(directLaunchConsumed || !prefs.getBoolean("launch_direct",false) ||
            prefs.getString("game_path","").isEmpty())return;
        directLaunchConsumed=true;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
            ()->withAccess(()->prepareSelected(true)));
    }
    private void selectTab(String tab) {
        if(busy)return;
        currentTab=tab;buildHome();
    }
    private void addTab(LinearLayout tabs,String title,String tab) {
        TextView label=text(title,12,currentTab.equals(tab)?0xffe9efd9:MUTED);
        label.setGravity(Gravity.CENTER);label.setTypeface(null,
            currentTab.equals(tab)?Typeface.BOLD:Typeface.NORMAL);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(currentTab.equals(tab)?0xff3e4e36:0xff1b2520);
        shape.setCornerRadius(dp(8));label.setBackground(shape);
        label.setOnClickListener(v->selectTab(tab));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(45),1);
        lp.setMargins(dp(2),0,dp(2),0);tabs.addView(label,lp);
    }
    private void choice(LinearLayout parent,String key,String heading,
                        String[] names,String[] values,String fallback) {
        TextView title=text(heading,15,TEXT);title.setTypeface(null,Typeface.BOLD);parent.addView(title);
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        String[] translated=new String[names.length];
        for(int i=0;i<names.length;i++)translated[i]=TlouLanguage.t(this,names[i]);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,translated);
        spinner.setAdapter(adapter);
        String value=prefs.getString(key,fallback);int initial=0;
        for(int i=0;i<values.length;i++)if(values[i].equals(value))initial=i;
        spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putString(key,values[index]).apply();refresh();
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46)));
    }
    private void toggleSetting(LinearLayout parent,String key,String label,boolean fallback) {
        Switch sw=new Switch(this);sw.setText(TlouLanguage.t(this,label));sw.setTextSize(14);
        sw.setTextColor(TEXT);sw.setPadding(0,dp(8),0,dp(8));
        sw.setChecked(prefs.getBoolean(key,fallback));
        sw.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean(key,checked).apply());
        parent.addView(sw);
    }
    /** A switch row: title and, under it, what the option does. */
    private void option(LinearLayout parent,String key,String title,String description,boolean fallback) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(6),0,dp(6));
        LinearLayout words=column();
        TextView heading=text(title,15,TEXT);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(2),0,dp(2));
        words.addView(heading);
        if(description!=null){TextView about=text(description,12,MUTED);about.setPadding(0,dp(2),0,dp(2));about.setLineSpacing(0,1.1f);words.addView(about);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Switch sw=new Switch(this);sw.setChecked(prefs.getBoolean(key,fallback));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(prefs.getBoolean(key,fallback)?ACCENT:0xff8c968a));
        sw.setOnCheckedChangeListener((v,checked)->{prefs.edit().putBoolean(key,checked).apply();
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(checked?ACCENT:0xff8c968a));});
        sw.setContentDescription(TlouLanguage.t(this,title));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(14);row.addView(sw,lp);
        row.setOnClickListener(v->sw.toggle());
        parent.addView(row);
    }
    /** A list choice with its explanation. */
    private void choiceRow(LinearLayout parent,String key,String title,String description,
                           String[] names,String[] values,String fallback) {
        choice(parent,key,title,names,values,fallback);
        if(description!=null)hint(parent,description);
    }
    private void hint(LinearLayout parent,String message) {
        TextView t=text(message,12,MUTED);t.setLineSpacing(0,1.1f);parent.addView(t);
    }
    private void divider(LinearLayout parent) {
        View line=new View(this);line.setBackgroundColor(0x22c2cf96);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.topMargin=dp(6);lp.bottomMargin=dp(6);
        parent.addView(line,lp);
    }
    private void languageChoice(LinearLayout parent) {
        TextView title=text("Idioma do aplicativo",15,TEXT);title.setTypeface(null,Typeface.BOLD);parent.addView(title);
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,TlouLanguage.options(this));
        spinner.setAdapter(adapter);
        int initial=TlouLanguage.selectedOption(this);spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putString("ui_language",TlouLanguage.selectedCode(index)).apply();
                buildHome(); // app language is immediate and never changes guest/game language
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void screenChoice(LinearLayout parent) {
        TextView title=text("Tela",15,TEXT);title.setTypeface(null,Typeface.BOLD);parent.addView(title);
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        String[] labels={
            TlouLanguage.t(this,"Ajustar à tela (proporção original)"),
            TlouLanguage.t(this,"Preencher tela (sem barras)")};
        spinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,labels));
        int initial=prefs.getBoolean("fullscreen",false)?1:0;
        spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putBoolean("fullscreen",index==1).apply();
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46)));
    }
    private void buildHome() {
        gameInfo=null;driverInfo=null;permission=null;
        buttons.clear();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root=column();root.setPadding(dp(18),dp(18),dp(18),dp(22));
        scroll.addView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        // This build's fork (the original port's credits stay in Sobre).
        TextView brand=text("Fork de royllll1999-hub · AYN Odin 3",12,ACCENT);brand.setLetterSpacing(.06f);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("v"+TlouDialogs.version(this),13,ACCENT);
        version.setGravity(Gravity.RIGHT);header.addView(version,new LinearLayout.LayoutParams(dp(72),-2));
        root.addView(header);
        if(currentTab.equals("home")) {
            // The hero is bundled with the APK: shown immediately and offline,
            // like the other Daedalus game launchers.
            // Keep the original hero image but cover its legacy "v0.1" mark with
            // the actual installed APK version (prevents mismatches on upgrades).
            FrameLayout artFrame=new FrameLayout(this);
            artFrame.setBackground(background(PANEL));
            artFrame.setClipToOutline(true);
            ImageView hero=new ImageView(this);
            hero.setImageResource(R.drawable.tlou_hero);
            hero.setContentDescription("The Last of Us Remastered");
            hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artFrame.addView(hero,new FrameLayout.LayoutParams(-1,-1));
            TextView artVersion=text("v"+TlouDialogs.version(this),11,TEXT);
            artVersion.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
            artVersion.setBackgroundColor(0xff101613);
            artVersion.setPadding(dp(9),dp(2),dp(9),dp(4));
            FrameLayout.LayoutParams artBadge=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
            artBadge.setMargins(0,dp(4),dp(5),0);
            artFrame.addView(artVersion,artBadge);
            LinearLayout.LayoutParams art=new LinearLayout.LayoutParams(-1,dp(210));
            art.topMargin=dp(8);art.bottomMargin=dp(13);
            root.addView(artFrame,art);
        } else {
            TextView title=text("THE LAST OF US",34,TEXT);
            title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));root.addView(title);
            TextView subtitle=text("R E M A S T E R E D",12,ACCENT);
            subtitle.setPadding(0,0,0,dp(14));root.addView(subtitle);
        }

        // Launcher layout (Odin 3 build): five tabs, one topic each. Every option carries a line
        // saying what it does; all texts go through TlouLanguage (ui-translations.tsv).
        if(currentTab.equals("patches")||currentTab.equals("settings")||currentTab.equals("about"))
            currentTab=currentTab.equals("patches")?"graphics":currentTab.equals("settings")?"performance":"system";
        LinearLayout tabs=new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        addTab(tabs,"Início","home");addTab(tabs,"Gráficos","graphics");
        addTab(tabs,"Desempenho","performance");addTab(tabs,"Controles","controls");
        addTab(tabs,"Sistema","system");
        LinearLayout.LayoutParams tabLP=new LinearLayout.LayoutParams(-1,dp(45));
        tabLP.bottomMargin=dp(18);root.addView(tabs,tabLP);

        if(currentTab.equals("home")) {
            LinearLayout game=card(root,"SEU JOGO");
            gameInfo=text("",13,TEXT);gameInfo.setMaxLines(3);game.addView(gameInfo);
            add(game,"JOGAR",true,()->withAccess(()->prepareSelected(true)));
            game.addView(text("PKG ou pasta extraída. Preparação automática no celular.",12,MUTED));
            add(game,"Escolher pasta do jogo",false,()->withAccess(()->pickFolder(PICK_GAME)));
            add(game,"Instalar por PKG",false,()->withAccess(()->pickFile(PICK_PKG)));
            TextView manual=text("Informar caminho da pasta",12,ACCENT);
            manual.setOnClickListener(v->{if(!busy)manualPath();});game.addView(manual);
            option(game,"launch_direct","Entrar direto no jogo",
                "Ao abrir o aplicativo, o jogo começa sozinho, sem passar por este menu.",false);
            LinearLayout updates=card(root,"ATUALIZAÇÃO DO JOGO");
            updates.addView(text("Aplique um PKG de update ou selecione uma pasta extraída. O jogo base e os saves não serão sobrescritos.",12,MUTED));
            add(updates,"Atualizar por PKG",false,()->withAccess(()->pickFile(PICK_UPDATE_PKG)));
            add(updates,"Atualizar por pasta",false,()->withAccess(()->pickFolder(PICK_UPDATE_FOLDER)));
            add(game,"Sair do aplicativo",false,this::finishAffinity);
        } else if(currentTab.equals("graphics")) {
            LinearLayout image=card(root,"RESOLUÇÃO E IMAGEM");
            choiceRow(image,"resolution","Resolução interna",
                "Resolução em que o jogo é desenhado. Menor = mais FPS e menos calor; 720p é o melhor equilíbrio no AYN Odin 3. 900p e 828p (no lugar de 810p, que não divide por igual) só na versão 1.11 do jogo. Reinicie o jogo para aplicar.",
                new String[]{"Original (1080p)","1600 × 900 (1.11)","1472 × 828 (1.11)","1280 × 720","960 × 540","640 × 360"},
                new String[]{"original","900","828","720","540","360"},"original");
            divider(image);
            screenChoice(image);
            hint(image,"Ajustar mantém a proporção 16:9 com faixas pretas; Preencher estica a imagem para a tela inteira.");
            divider(image);
            option(image,"hair_dma","Iluminação correta de cabelo e barba (experimental)",
                "Corrige cabelo e barba dourados em cenas escuras: os shaders deles leem as sombras do sol direto da memória do jogo, como no port para PC. Pode custar alguns FPS. Na primeira vez os shaders são preparados de novo (alguns travamentos curtos nos primeiros minutos). Reinicie o jogo para aplicar.",false);
            LinearLayout fsr=card(root,"AMPLIAÇÃO (FSR / SGSR)");
            option(fsr,"fsr","Ativar ampliação da imagem",
                "Melhora a nitidez da imagem quando o jogo roda abaixo da resolução da tela.",false);
            divider(fsr);
            choiceRow(fsr,"upscaler","Método de ampliação",
                "AMD FSR 1.0: duas passadas (ampliação + nitidez RCAS ajustável). Snapdragon GSR: uma passada feita para GPUs Adreno, com nitidez própria; costuma custar menos GPU. Reinicie o jogo para aplicar.",
                new String[]{"AMD FSR 1.0","Snapdragon GSR"},
                new String[]{"fsr","sgsr"},"fsr");
            divider(fsr);
            choiceRow(fsr,"fsr_sharpness","Nitidez do FSR",
                "Força do filtro de nitidez (RCAS). Suave deixa a imagem mais macia; Forte, mais definida.",
                new String[]{"Suave","Média","Forte"},
                new String[]{"50","75","90"},"75");
            hint(fsr,"Como ativar: ligue a opção acima, escolha uma resolução abaixo de 1080p em Resolução interna e reinicie o jogo.");
            hint(fsr,"Como desativar: desligue a opção acima e reinicie o jogo. A imagem volta à exibição original.");
            hint(fsr,"Em 1080p (Original) só a nitidez RCAS é aplicada (também com Snapdragon GSR).");
            LinearLayout pacing=card(root,"FPS E SINCRONIZAÇÃO");
            choiceRow(pacing,"fps_limit","Limite de FPS",
                "Teto de quadros por segundo. 60 é o padrão; 30 reduz bastante o aquecimento e o consumo de bateria. 40 coloca a tela em 120 Hz para que cada quadro dure exatamente 3 atualizações (fluido, sem trepidação); se a tela não tiver 120 Hz, fica irregular.",
                new String[]{"30 FPS","40 FPS (tela 120 Hz)","60 FPS"},
                new String[]{"30","40","60"},"60");
            divider(pacing);
            option(pacing,"vsync","VSync",
                "Sincroniza os quadros com a tela para evitar imagem cortada (tearing). Recomendado ligado.",true);
            divider(pacing);
            option(pacing,"fps_unlock","Desbloquear FPS (até 120, experimental)",
                "O desbloqueio desliga o VSync durante a sessão para evitar o teto de 60 FPS. Cinemáticas mantêm o ritmo de 60 Hz.",false);
            LinearLayout effects=card(root,"EFEITOS GRÁFICOS");
            hint(effects,"Desligar efeitos alivia a GPU e aumenta o FPS. Patches validados para o eboot reconhecido; reinicie o jogo para aplicar.");
            option(effects,"ssao","Desativar SSAO",
                "Sombras suaves de contato nos cantos e entre objetos. Desligar dá FPS com pouca perda visual.",true);
            divider(effects);
            option(effects,"motion_blur","Desativar desfoque de movimento",
                "Borrão ao girar a câmera rapidamente. Muitos jogadores preferem sem ele.",true);
            divider(effects);
            option(effects,"depth_of_field","Desativar profundidade de campo",
                "Desfoque do fundo ao mirar e em cenas próximas.",true);
            divider(effects);
            option(effects,"bloom","Desativar bloom",
                "Brilho ao redor de luzes fortes e do céu.",true);
            divider(effects);
            option(effects,"sunlight_shadows","Desativar sombras solares",
                "Sombras projetadas pelo sol em áreas abertas. O maior ganho de FPS, mas a cena fica mais plana.",true);
            divider(effects);
            option(effects,"subsurface","Desativar subsurface",
                "Luz que atravessa a pele dos personagens (rostos mais naturais). Desligar alivia a GPU em diálogos.",true);
            divider(effects);
            option(effects,"omni_light_shadows","Desativar sombras de luzes omni (1.11)",
                "Sombras de lâmpadas e luzes pontuais. Somente na versão 1.11; experimental.",false);
            divider(effects);
            option(effects,"spot_light_shadows","Desativar sombras de holofotes (1.11)",
                "Sombras de lanternas e holofotes. Somente na versão 1.11; experimental.",false);
            add(effects,"Restaurar patches e resolução",false,()->{
                SharedPreferences.Editor edit=prefs.edit().putString("resolution","original");
                for(String key:PortSettings.EFFECTS)edit.putBoolean(key,false);
                edit.putBoolean("fps_unlock",false);
                edit.putBoolean("fsr",false).putString("fsr_sharpness","75").putString("upscaler","fsr");
                edit.apply();buildHome();
            });
        } else if(currentTab.equals("performance")) {
            LinearLayout cpu=card(root,"CPU E DESEMPENHO");
            hint(cpu,"Estas opções reduzem o trabalho do processador; nenhuma aumenta a frequência da CPU. Reinicie o jogo para aplicar.");
            option(cpu,"job_wake","Despertar rápido dos threads do jogo",
                "Os threads de tarefas do jogo acordam assim que há trabalho, em vez de esperar até 10 ms (como no port de Uncharted para Android). Mais FPS e menos travadas, sem esquentar mais. Reinicie o jogo para aplicar; desligue se notar instabilidade.",true);
            divider(cpu);
            option(cpu,"pause_wfe","Espera econômica dos threads (menos aquecimento)",
                "Quando um thread do jogo fica girando à espera de outro, o processador dorme até o próximo evento em vez de consumir energia (como no port de Uncharted para Android). Menos calor e bateria; em raros casos pode custar alguns FPS. Desligado por padrão; reinicie o jogo para aplicar.",false);
            divider(cpu);
            option(cpu,"gpu_thread_priority","Prioridade dos threads de renderização",
                "Os três threads que enviam o trabalho para a GPU ganham prioridade um pouco maior que os threads de tarefas do jogo, para não ficarem na fila atrás deles. No port para PC isso evitou quedas de 47 para 33 FPS. Não aumenta a frequência da CPU. Ligado por padrão; reinicie o jogo para aplicar.",true);
            divider(cpu);
            option(cpu,"fex_no_tso","Modo rápido da CPU sem TSO (experimental)",
                "Desliga a emulação da ordem de memória do x86 no FEX: o código do jogo roda mais rápido, mas o próprio FEX avisa que isso pode quebrar programas com vários threads (travamentos, objetos estranhos, quedas). Teste curto: se algo der errado, desligue. Reinicie o jogo para aplicar.",false);
            divider(cpu);
            option(cpu,"movie_area","Vídeos: redução rápida em uma passada (NEON)",
                "Os vídeos das cinemáticas (1080p) são reduzidos para 720p numa só passada, com menos trabalho da CPU. Desligado = o método original do port, em duas passadas. Se o jogo travar no início de uma cinemática, desligue para testar. Reinicie o jogo para aplicar.",true);
            divider(cpu);
            option(cpu,"fast_render_cores","Priorizar núcleos rápidos (experimental)",
                "Não força o clock. Limita as threads críticas aos núcleos mais rápidos; reinicie o jogo para aplicar. Pode aumentar a disputa entre threads.",false);
            divider(cpu);
            option(cpu,"adpf","ADPF — ajuste dinâmico de desempenho",
                "Informa ao Android quanto tempo cada quadro leva, para o sistema ajustar a CPU por conta própria (Android 13+). Não desativa os limites térmicos.",false);
            LinearLayout drivers=card(root,"GPU E DRIVER VULKAN");
            driverInfo=text("",12,MUTED);drivers.addView(driverInfo);
            add(drivers,"Selecionar driver Vulkan",false,this::selectDriver);
            add(drivers,"Importar driver ZIP ou SO",false,()->pickFile(PICK_DRIVER));
            hint(drivers,"O driver Turnip incluído é o recomendado. Troque só para testar outra versão; o jogo usa o driver escolhido na próxima vez que abrir.");
            divider(drivers);
            option(drivers,"gpu_turbo","Adreno Turbo (sem root)",
                "Política KGSL de máximo desempenho; a proteção térmica continua ativa. Pode não funcionar em todos os dispositivos.",false);
            LinearLayout diagnostics=card(root,"DIAGNÓSTICO");
            option(diagnostics,"shader_scan_scalar","Diagnóstico CPU: usar busca original (A/B)",
                "Desligado = NEON; ligado = original. Reinicie o jogo entre os testes e exporte os logs.",false);
            divider(diagnostics);
            option(diagnostics,"render_profile","Perfil de renderização (para otimização)",
                "Grava no log quanto tempo de GPU cada etapa do quadro leva e onde a CPU espera. Jogue 2–3 minutos na cena que pesa, saia e exporte os logs. Custa alguns FPS: desligue depois. Reinicie o jogo para aplicar.",false);
            hint(diagnostics,"Modo Unificado padrão: jogo, interface e mira na mesma resolução. Vídeos mantêm a decodificação original.");
            hint(diagnostics,"Sincronização da geometria: Completo (único modo). "
                +"Correção de deformações mantida sem opções que não melhoraram o FPS.");
        } else if(currentTab.equals("controls")) {
            LinearLayout touch=card(root,"CONTROLES NA TELA");
            choiceRow(touch,"touch_mode","Controles na tela",
                "Botões virtuais por cima do jogo. Automático os esconde quando há um controle físico, como os botões do Odin.",
                new String[]{"Automático (ocultar com controle conectado)","Sempre mostrar","Desligado"},
                new String[]{"auto","on","off"},"auto");
            divider(touch);
            choiceRow(touch,"control_color","Cor dos controles",
                "Cor dos botões virtuais na tela.",
                new String[]{"Verde oliva","Âmbar","Branco","Vermelho","Azul"},
                new String[]{"olive","amber","white","red","blue"},"olive");
            hint(touch,"Editar posição dos botões: deslize da borda esquerda para abrir o menu.");
            LinearLayout hud=card(root,"HUD DE DESEMPENHO");
            option(hud,"hud_visible","Mostrar FPS",
                "Mostra o contador de desempenho por cima do jogo.",true);
            divider(hud);
            choiceRow(hud,"hud_mode","Estilo do HUD",
                "Quanta informação aparece: completa (FPS, GPU e CPU), FPS com tempo de quadro ou apenas FPS.",
                new String[]{"Completa (FPS / GPU / CPU)","FPS + tempo de frame","Apenas FPS"},
                new String[]{"full","compact","minimal"},"full");
            divider(hud);
            choiceRow(hud,"hud_corner","Posição do HUD",
                "Onde o contador aparece. Personalizada: arraste-o com o dedo durante o jogo.",
                new String[]{"Superior esquerdo","Superior direito","Inferior esquerdo",
                    "Inferior direito","Superior central","Centro da tela",
                    "Inferior central","Central esquerdo","Central direito",
                    "Personalizada (arrastar)"},
                new String[]{"0","1","2","3","4","5","6","7","8","-1"},"0");
            LinearLayout motion=card(root,"SENSORES DE MOVIMENTO");
            hint(motion,"Em The Last of Us, sacudir o controle conserta a lanterna quando ela começa a piscar. Em um portátil como o AYN Odin 3, o próprio aparelho faz o papel do controle.");
            option(motion,"motion_shake","Sacudir o aparelho = sacudir o controle",
                "Como ativar: ligue a opção acima e, quando a lanterna piscar, sacuda o aparelho com força algumas vezes. Vale na hora, sem reiniciar.",true);
            divider(motion);
            choiceRow(motion,"motion_sensitivity","Sensibilidade da sacudida",
                "Quão forte é preciso sacudir. Alta reage a movimentos leves; Baixa evita acionamentos acidentais.",
                new String[]{"Baixa","Média","Alta"},
                new String[]{"low","medium","high"},"medium");
            divider(motion);
            option(motion,"shake_button","L3 + R3 juntos = sacudir",
                "Apertar os dois analógicos ao mesmo tempo também sacode a lanterna, útil com o aparelho apoiado.",true);
            hint(motion,"Como desativar: desligue a opção acima. Se L3 + R3 estiver ligado, a sacudida pelos botões continua disponível.");
        } else {
            LinearLayout app=card(root,"IDIOMA E INTERFACE");
            languageChoice(app);
            hint(app,"O idioma do jogo é escolhido no próprio The Last of Us.");
            LinearLayout data=card(root,"SAVES");
            hint(data,"Cópia de segurança dos jogos salvos em um arquivo ZIP. Exporte antes de reinstalar o aplicativo ou trocar de versão.");
            add(data,"Exportar saves ZIP",false,this::exportSaves);
            add(data,"Importar saves ZIP",false,()->pickFile(IMPORT_SAVES));
            root.addView(AboutPanel.create(this,this::showLog,this::exportLogs));
        }

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress);
        status=text("",12,MUTED);status.setVisibility(View.GONE);root.addView(status);
        permission=text("Permitir acesso aos arquivos",12,ACCENT);
        permission.setPadding(0,dp(10),0,dp(10));
        permission.setOnClickListener(v->withAccess(this::refresh));root.addView(permission);
        setContentView(scroll);refresh();
    }
    /** Updates belong to the selected game's CUSA, never to the Android APK. */
    private File baseForUpdate() throws Exception {
        String configured=prefs.getString("game_path","");
        if(configured.isEmpty())throw new IOException("Selecione a pasta do jogo base antes de instalar a atualização.");
        return GameInstallState.base(new File(configured));
    }

    private File updateFolder(File folder) throws Exception {
        File root=folder.getCanonicalFile();
        if(new File(root,"sce_sys/param.sfo").isFile())return root;
        File app0=new File(root,"app0");
        if(new File(app0,"sce_sys/param.sfo").isFile())return app0.getCanonicalFile();
        File[] children=root.listFiles(File::isDirectory);
        if(children!=null && children.length==1){
            File child=children[0];
            if(new File(child,"sce_sys/param.sfo").isFile())return child.getCanonicalFile();
            File nested=new File(child,"app0");
            if(new File(nested,"sce_sys/param.sfo").isFile())return nested.getCanonicalFile();
        }
        throw new IOException("Selecione a pasta extraída da atualização que contém sce_sys/param.sfo.");
    }

    private void applyGameUpdate(File chosenFolder) {
        task("Aplicando atualização do jogo…",()->{
            File game=baseForUpdate();
            File patch=updateFolder(chosenFolder);
            if(game.equals(patch))throw new IOException("A pasta de atualização deve ser diferente da pasta do jogo base.");
            File prepared=tools.prepare(game,patch,this::update);
            // Only publish the new update after preparation and patch validation
            // succeed; previously playable output and saves remain untouched.
            prefs.edit().putString("update_game",game.getAbsolutePath())
                .putString("update_path",patch.getAbsolutePath())
                .putString("prepared_path",prepared.getAbsolutePath()).apply();
            update("Atualização aplicada. Toque em JOGAR.");
        });
    }

    private File automaticUpdateDestination(File game) throws IOException {
        // Use the existing game library as the extraction parent: updating a
        // game must never require a second folder-picker interaction.
        File parent=game.getCanonicalFile().getParentFile();
        if(parent != null && parent.isDirectory() && parent.canWrite())return parent;
        File fallback=getExternalFilesDir("game-updates");
        if(fallback==null || (!fallback.isDirectory()&&!fallback.mkdirs()) || !fallback.canWrite())
            throw new IOException("Sem uma pasta gravável para extrair o PKG de atualização.");
        return fallback.getCanonicalFile();
    }

    private void installGameUpdatePkg(File pkg) {
        task("Extraindo e aplicando atualização…",()->{
            File parent=automaticUpdateDestination(baseForUpdate());
            File game=baseForUpdate();
            File destination=parent.getCanonicalFile();
            File prepared=new File(getFilesDir(),"tlou-prepared").getCanonicalFile();
            if(destination.toPath().startsWith(game.toPath()) ||
               destination.toPath().startsWith(prepared.toPath()))
                throw new IOException("Escolha um destino fora das pastas do jogo e da preparação.");
            File container=new File(destination,"TLOU-Update-"+UUID.randomUUID()).getCanonicalFile();
            if(!container.mkdir())throw new IOException("Não foi possível criar a pasta do update.");
            boolean installed=false;
            try {
                File patch=tools.extractUpdate(pkg,container,this::update);
                File updated=tools.prepare(game,patch,this::update);
                prefs.edit().putString("update_game",game.getAbsolutePath())
                    .putString("update_path",patch.getAbsolutePath())
                    .putString("prepared_path",updated.getAbsolutePath()).apply();
                installed=true;
                update("PKG de atualização aplicado. Toque em JOGAR.");
            } finally {
                if(!installed)InstallTools.delete(container);
            }
        });
    }

    private File updateForGame(File game) throws Exception {
        return GameInstallState.update(game,selected(),new File(getFilesDir(),"tlou-prepared"),prefs);
    }

    private void exportLogs() {
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.setType("application/zip");save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE,"TLOU-Android-Logs.zip");startActivityForResult(save,SAVE_LOGS);
    }
    private File saveUser(){return new File(getFilesDir(),"tlou/user");}
    private void exportSaves() {
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.setType("application/zip");save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE,"TLOU-Saves-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new Date())+".zip");
        startActivityForResult(save,EXPORT_SAVES);
    }
    private void openReference(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String message,int size,int color) {
        TextView t=new TextView(this); t.setText(TlouLanguage.t(this,message)); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(7)); return t;
    }
    private GradientDrawable background(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); return d; }
    private LinearLayout card(LinearLayout root,String title) {
        LinearLayout c=column(); c.setPadding(dp(18),dp(14),dp(18),dp(14));
        GradientDrawable bg=background(PANEL); bg.setStroke(dp(1),0x33c2cf96); c.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(14); root.addView(c,lp);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        View mark=new View(this); GradientDrawable dot=new GradientDrawable(); dot.setColor(ACCENT); dot.setCornerRadius(dp(2)); mark.setBackground(dot);
        LinearLayout.LayoutParams ml=new LinearLayout.LayoutParams(dp(4),dp(16)); ml.rightMargin=dp(10); head.addView(mark,ml);
        TextView heading=text(title,12,ACCENT); heading.setLetterSpacing(0.12f); heading.setTypeface(null,Typeface.BOLD); head.addView(heading);
        LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(-1,-2); hl.bottomMargin=dp(6); c.addView(head,hl); return c;
    }
    private void add(LinearLayout parent,String label,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(TlouLanguage.t(this,label)); b.setTextSize(14); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setTextColor(primary?BACKGROUND:TEXT); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?ACCENT:0xff303c33));
        b.setOnClickListener(v->{if(!busy)action.run();}); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50)); p.topMargin=dp(8); parent.addView(b,p); buttons.add(b);
    }
    private void alert(String title,String message) { if(!isFinishing()&&!isDestroyed())TlouDialogs.show(this,new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,title)).setMessage(TlouLanguage.t(this,message)).setPositiveButton("OK",null)); }
    private void update(String message) {
        runOnUiThread(()->{if(!isDestroyed() && status!=null)
            {status.setText(TlouLanguage.t(this,message));status.setVisibility(View.VISIBLE);}});
    }
    private boolean access() { return Build.VERSION.SDK_INT<30 || Environment.isExternalStorageManager(); }
    private void withAccess(Runnable action) {
        if(access()){action.run();return;}
        afterPermission=action;
        try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()))); }
        catch(Exception e){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
    }
    private void pickFile(int code) { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,code); }
    private void pickFolder(int code) { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,code); }
    private void refresh() {
        if(gameInfo==null && driverInfo==null && permission==null)return;
        String folder=prefs.getString("game_path","");
        if(gameInfo!=null)gameInfo.setText(folder.isEmpty()?TlouLanguage.t(this,"Nenhum jogo selecionado"):folder);
        if(driverInfo!=null)driverInfo.setText(PortSettings.summary(this)+"\n"+prefs.getString("driver_name",TlouLanguage.t(this,"Driver do sistema")));
        if(permission!=null)permission.setVisibility(access()?View.GONE:View.VISIBLE);
    }
    private void task(String message, Work work) {
        if(busy)return; busy=true; for(Button b:buttons)b.setEnabled(false); progress.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); update(message);
        worker.execute(()->{try{work.run();}catch(Exception e){RunLog.failure(this,"Operação do launcher",e);update("A operação não foi concluída."); runOnUiThread(()->alert("Não foi possível concluir",e.getMessage()==null?e.toString():e.getMessage()));}
            finally{runOnUiThread(()->{busy=false;if(isDestroyed())return;for(Button b:buttons)b.setEnabled(true);progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);refresh();});}});
    }
    private interface Work { void run() throws Exception; }
    private File selected() { return new File(prefs.getString("game_path","")); }
    private void chooseGame(File folder) {
        if(!InstallTools.prepared(folder)&&!new File(folder,"eboot.bin").isFile()) {alert("Pasta do jogo","Escolha a pasta que contém eboot.bin e sce_sys, ou uma pasta já preparada.");return;}
        prefs.edit().putString("game_path",folder.getAbsolutePath()).apply(); refresh(); prepareSelected(false);
    }
    private void prepareSelected(boolean launch) {
        if(busy)return;
        if(launch){RunLog.begin(this);RunLog.write(this,"Jogo selecionado: "+prefs.getString("game_path","")+" • driver="+prefs.getString("driver_name","Sistema"));}
        if(prefs.getString("game_path","").isEmpty()){alert("Selecione o jogo","Instale um PKG ou escolha a pasta do jogo.");return;}
        task("Verificando e preparando jogo…",()->{
            // Finish first-run Adreno 6xx/7xx driver setup before the game
            // captures its launch environment, without overriding user choice.
            try { BuiltinDrivers.ensureDefault(this); }
            catch(Exception e) { android.util.Log.w("TLOU", "Default Turnip unavailable", e); }
            // Always prepare from the original game plus the persisted update.
            // A prepared directory is a cache, never a new base game: treating it
            // as a base lost the 01.11 overlay after APK upgrades/migrations.
            File base=GameInstallState.base(selected());
            File updateFolder=updateForGame(base);
            File prepared=tools.prepare(base,updateFolder,this::update);
            SharedPreferences.Editor saved=prefs.edit()
                .putString("game_path",base.getAbsolutePath())
                .putString("prepared_path",prepared.getAbsolutePath());
            if(updateFolder!=null)
                saved.putString("update_game",base.getAbsolutePath())
                     .putString("update_path",updateFolder.getAbsolutePath());
            saved.apply();
            update("Pronto para jogar.");
            if(launch)runOnUiThread(()->{if(!isDestroyed()){try { RunLog.write(this,"Preparação verificada. Abrindo GameActivity: "+prepared); Intent i=new Intent(this,GameActivity.class);i.putExtra("prepared_path",prepared.getAbsolutePath());startActivity(i); } catch(Throwable error) { RunLog.failure(this,"Falha ao abrir GameActivity",error);alert("Inicialização",error.toString()); }}});
        });
    }
    private void manualPath() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setText(prefs.getString("game_path",""));input.setHint("/storage/emulated/0/Jogos/CUSA00552");
        TlouDialogs.show(this,new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"Pasta do jogo")).setView(input).setNegativeButton(TlouLanguage.t(this,"Cancelar"),null).setPositiveButton(TlouLanguage.t(this,"Selecionar"),(d,w)->withAccess(()->chooseGame(new File(input.getText().toString().trim())))));
    }
    private void installPkg(File pkg,File parent) {
        task("Instalando PKG…",()->{
            File extracted=tools.extract(pkg,parent,this::update);
            // Save extracted folder even if preparation fails; never force another extraction.
            prefs.edit().putString("game_path",extracted.getAbsolutePath())
                .remove("update_path").remove("update_game").apply();
            File prepared=tools.prepare(extracted,this::update);
            prefs.edit().putString("prepared_path",prepared.getAbsolutePath()).apply(); update("Jogo instalado e preparado. Toque em JOGAR.");
        });
    }
    private String displayName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}
        return "Driver importado";
    }
    private void importDriver(Uri uri) {
        String name=displayName(uri);
        task("Importando driver…",()->{
            File drivers=new File(getFilesDir(),"drivers");
            try(BufferedInputStream in=new BufferedInputStream(Objects.requireNonNull(getContentResolver().openInputStream(uri)))) {
                in.mark(4); int a=in.read(),b=in.read();in.reset();
                if(a=='P'&&b=='K') {
                    DriverArchive.Installed driver=DriverArchive.install(drivers,in,Build.VERSION.SDK_INT,file->{
                        JSONObject j=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                        return new DriverArchive.Metadata(j.getString("name"),j.getString("libraryName"),j.getInt("minApi"));
                    });
                    prefs.edit().putString("driver_path",driver.library.getAbsolutePath()).putString("driver_name",driver.name).apply();
                } else {
                    File raw=new File(drivers,"raw");if(!raw.isDirectory()&&!raw.mkdirs())throw new IOException("Falha ao criar pasta de drivers");
                    File staged=File.createTempFile("driver-",".so",raw);
                    try {
                        try(OutputStream out=new FileOutputStream(staged)){byte[] bytes=new byte[65536];long total=0;int n;while((n=in.read(bytes))!=-1){total+=n;if(total>256L*1024*1024)throw new IOException("Driver grande demais");out.write(bytes,0,n);}}
                        DriverArchive.validateLibrary(staged);
                        Files.write(new File(staged.getPath()+".name").toPath(),name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        prefs.edit().putString("driver_path",staged.getAbsolutePath()).putString("driver_name",name).apply();
                    }catch(Exception e){Files.deleteIfExists(staged.toPath());throw e;}
                }
            }catch(DriverArchive.Rejected e){throw new IOException(e.reason==DriverArchive.Reason.NEWER_ANDROID?"Este driver exige uma versão mais recente do Android":"Pacote inválido. Use um driver Vulkan Android ARM64 com meta.json e biblioteca .so.",e);}
            update("Driver importado e selecionado.");
        });
    }
    private void selectDriver() {
        List<String> names=new ArrayList<>(),paths=new ArrayList<>();names.add(TlouLanguage.t(this,"Driver do sistema"));paths.add("");
        File root=new File(getFilesDir(),"drivers");File[] dirs=root.listFiles();
        if(dirs!=null)for(File d:dirs)try{
            if(d.getName().equals("raw")){
                File[] raw=d.listFiles((dir,name)->name.endsWith(".so"));if(raw!=null)for(File f:raw){File n=new File(f.getPath()+".name");names.add(n.isFile()?new String(Files.readAllBytes(n.toPath()),java.nio.charset.StandardCharsets.UTF_8):f.getName());paths.add(f.getAbsolutePath());}
            }else if(!d.getName().startsWith(".")){DriverArchive.Installed imported=DriverArchive.read(root,d.getName(),Build.VERSION.SDK_INT);names.add(imported.name);paths.add(imported.library.getAbsolutePath());}
        }catch(Exception ignored){}
        String current=prefs.getString("driver_path","");int checked=Math.max(0,paths.indexOf(current));
        TlouDialogs.show(this,new AlertDialog.Builder(this).setTitle("Driver Vulkan").setSingleChoiceItems(names.toArray(new String[0]),checked,(dialog,index)->{
            prefs.edit().putString("driver_path",paths.get(index)).putString("driver_name",names.get(index)).apply();refresh();dialog.dismiss();
        }).setNegativeButton(TlouLanguage.t(this,"Cancelar"),null));
    }
    private File local(Uri uri,boolean tree) throws Exception {
        String raw=null;
        if("com.android.externalstorage.documents".equals(uri.getAuthority())){
            String id=tree?DocumentsContract.getTreeDocumentId(uri):DocumentsContract.getDocumentId(uri);int colon=id.indexOf(':');if(colon<1)throw new IOException("Volume inválido");
            String volume=id.substring(0,colon);if(!volume.matches("[A-Za-z0-9_-]+"))throw new IOException("Volume inválido");
            File root=volume.equalsIgnoreCase("primary")?Environment.getExternalStorageDirectory():new File("/storage",volume);
            File result=new File(root,id.substring(colon+1)).getCanonicalFile();if(!result.toPath().startsWith(root.getCanonicalFile().toPath()))throw new IOException("Pasta inválida");return result;
        }
        if(!tree){
            try{String id=DocumentsContract.getDocumentId(uri);if(id.startsWith("raw:"))raw=id.substring(4);}catch(Exception ignored){}
            if(raw==null)try(Cursor c=getContentResolver().query(uri,new String[]{"_data"},null,null,null)){if(c!=null&&c.moveToFirst())raw=c.getString(0);}catch(Exception ignored){}
        }
        if(raw!=null){File f=new File(raw).getCanonicalFile();if(f.toPath().startsWith(new File("/storage").toPath()))return f;}
        throw new IOException("Escolha um arquivo ou pasta local pelo armazenamento interno/USB. Para pastas, você também pode informar o caminho.");
    }
    private void showLog() {
        TlouDialogs.showLogs(this,RunLog.summary(this),this::exportLogs);
    }
    @Override protected void onResume(){super.onResume();refresh();if(afterPermission!=null){Runnable action=afterPermission;afterPermission=null;if(access())action.run();}
        else if(access()&&!prefs.getBoolean("onboarding_seen",false)&&!firstRunDialogShown)showFirstRunSetup();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);
        if(pendingPkg!=null)state.putString("pending_pkg",pendingPkg.toString());
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){
            if(code==PICK_DEST)pendingPkg=null;
            return;
        }Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}
        try{
            if(code==SAVE_LOGS){task("Exportando logs…",()->{java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w");if(out==null)throw new IOException("Não foi possível abrir o destino");RunLog.export(this,out);update("Logs ZIP salvos.");});}
            else if(code==EXPORT_SAVES){task("Exportando saves…",()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                    if(out==null)throw new IOException("Não foi possível abrir o destino");
                    SaveArchive.exportZip(saveUser(),out);
                }update("Saves exportados com sucesso.");
            });}
            else if(code==IMPORT_SAVES){task("Importando saves…",()->{
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new IOException("Não foi possível abrir o ZIP");
                    File backup=SaveArchive.importZip(saveUser(),in);
                    RunLog.write(this,"Saves importados; backup anterior: "+(backup==null?"sem saves anteriores":backup.getAbsolutePath()));
                    update(backup==null?"Saves importados com sucesso.":"Saves importados. Os anteriores foram preservados em backup.");
                }
            });}
            else if(code==PICK_GAME)chooseGame(local(uri,true));
            else if(code==PICK_DRIVER)importDriver(uri);
            else if(code==PICK_PKG){pendingPkg=uri;TlouDialogs.show(this,new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"Destino do jogo")).setMessage(TlouLanguage.t(this,"Escolha onde criar a pasta CUSA00552. O PKG será extraído diretamente para ela; depois o app prepara o jogo automaticamente.")).setPositiveButton(TlouLanguage.t(this,"Escolher destino"),(d,w)->pickFolder(PICK_DEST)).setNegativeButton(TlouLanguage.t(this,"Cancelar"),(d,w)->pendingPkg=null));}
            else if(code==PICK_DEST&&pendingPkg!=null){Uri pkg=pendingPkg;pendingPkg=null;installPkg(local(pkg,false),local(uri,true));}
            else if(code==PICK_UPDATE_FOLDER){baseForUpdate();applyGameUpdate(local(uri,true));}
            else if(code==PICK_UPDATE_PKG){
                baseForUpdate();
                installGameUpdatePkg(local(uri,false));
            }
        }catch(Exception e){alert("Seleção",e.getMessage());}
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}

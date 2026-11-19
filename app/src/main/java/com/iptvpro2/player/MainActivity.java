package com.iptvpro2.player;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.security.MessageDigest;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends Activity {
  private static final String SERVER="http://lgpro.top";
  private static final int BG=0xff030811, PANEL=0xff15102b, BLUE=0xff8d3bfa;
  private LinearLayout root;
  private final ExecutorService artworkExecutor=Executors.newFixedThreadPool(3);
  private final android.util.LruCache<String,Bitmap> artworkCache=new android.util.LruCache<String,Bitmap>(24);
  private boolean rememberCredentials=true;
  private String username="",password="",currentType="",currentCategory="",currentSeries="";
  private String currentMediaTitle="";
  private final Map<String,JSONArray> cachedCategories=new HashMap<>();
  private final Map<String,String> categoryPosters=new ConcurrentHashMap<>();
  private final Map<String,ArrayList<ImageView>> categoryImageViews=new HashMap<>();
  private final Set<String> categoryPosterRequests=Collections.synchronizedSet(new HashSet<>());
  private boolean authenticated=false;
  private boolean loginPending=false;
  private long lastLoginAttempt=0;
  private final ThreadPoolExecutor filterExecutor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new LinkedBlockingDeque<Runnable>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ThreadPoolExecutor networkExecutor=new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,new LinkedBlockingDeque<Runnable>(8),new ThreadPoolExecutor.DiscardOldestPolicy());
  private int requestGeneration=0;
  private int playbackGeneration=0;
  private boolean resumeApplied=false;
  private int fallbackBudget=0;
  private int filterGeneration=0;
  private int fallbackAttempts=0;
  private boolean userRequestedPause=false;
  private ExoPlayer player;
  private boolean fullscreenPlayer=false;
  private LinearLayout playerScreenRoot;
  private PlayerView activePlayerView;
  private ScrollView activePlayerControls;
  private TextView activePlayerHeading;
  private View activePlayerBack;
  private TextView activePlayerStatus;
  private int screenToken=0;
  private Runnable backAction;
  private String currentListTitle="";
  private JSONArray currentList;
  private String currentListKey;
  private Pick currentPick;
  private Runnable currentListBack;
  private String playerBackLabel="";
  private Runnable playerBack;
  private String activeStreamUrl="";
  private boolean playbackScreen=false, resumeOnForeground=false;
  private boolean wasPlayingBeforeStop=false;
  private long lastPosition=0;
  private int listPage=0;
  private String listFilter="";
  private final Handler downloadRefreshHandler=new Handler(Looper.getMainLooper());
  private Runnable downloadRefresh;
  private static final int PAGE_SIZE=80;
  private final Handler searchHandler=new Handler(Looper.getMainLooper());
  private Runnable pendingSearch;
  private final Handler heroHandler=new Handler(Looper.getMainLooper());
  private Runnable heroRotation;
  private int heroIndex=0;
  private String currentMediaKey="", currentMediaType="";
  private String currentStreamId="",currentStreamType="",currentStreamExt="";
  private TextView playbackStatus;
  private int alternateIndex=0;
  private String[] alternatives=new String[0];
  private String positionKey(){
    try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((username+"@"+SERVER+"|"+currentMediaKey).getBytes("UTF-8"));
      StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }catch(Exception e){return username+"|"+currentMediaKey;}
  }
  private SharedPreferences positions(){return getSharedPreferences("playback_positions",MODE_PRIVATE);}
  private void savePosition(){
    if(player!=null && !currentMediaKey.isEmpty() && !"live".equals(currentMediaType)){
      long pos=player.getCurrentPosition();if(player.getPlaybackState()==Player.STATE_ENDED)return;if(pos>5000)positions().edit().putLong(positionKey(),pos).apply();
    }
  }

  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    SharedPreferences saved=getSharedPreferences("login_prefs",MODE_PRIVATE);
    String savedUser=saved.getString("last_user","");
    String savedPass=saved.getString("last_pass","");
    if(saved.getBoolean("remember",true)&&!savedUser.isEmpty()&&!savedPass.isEmpty()){
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
      rememberCredentials=true;
      username=savedUser;password=savedPass;authenticated=true;
      home(); // Render immediately from the last local catalog; verify asynchronously.
      verifySavedLogin(savedUser,savedPass);
    }else login();
  }
  private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(18,16,18,16);return t;}
  private GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);if(border!=0)d.setStroke(2,border);return d;}
  private void screen(String title){stopHeroRotation();stopDownloadRefresh();screenToken++;requestGeneration++;playbackScreen=false;root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setPadding(dp(18),dp(14),dp(18),dp(16));setContentView(root);TextView heading=text(title,24);heading.setTypeface(null,Typeface.BOLD);heading.setTextColor(0xffeaf2ff);root.addView(heading);}
  private void safeAction(Runnable action){try{action.run();}catch(Exception e){android.util.Log.e("IPTV_PRO2","Action failed",e);toast("Erreur: "+e.getClass().getSimpleName());}}
  private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);b.setTextColor(Color.WHITE);b.setBackground(shape(0xff173a79,dp(12),0xff315d9b));b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(12),dp(8),dp(12),dp(8));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(43));lp.setMargins(0,dp(4),0,dp(4));b.setLayoutParams(lp);b.setOnClickListener(v->safeAction(action));return b;}
  private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextSize(16);e.setHintTextColor(0xff94a5bf);e.setTextColor(Color.WHITE);e.setPadding(22,24,22,24);e.setBackground(shape(PANEL,20,0xff334563));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
  private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
  private TextView centered(String value,int size,int color){TextView t=text(value,size);t.setGravity(Gravity.CENTER);t.setTextColor(color);return t;}
  private GradientDrawable gradient(int first,int second,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{first,second});d.setCornerRadius(dp(radius));return d;}
  private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  private void addSpace(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
  private View logoMark(){
    View mark=new View(this){Paint paint=new Paint(3);@Override protected void onDraw(Canvas canvas){
      super.onDraw(canvas);float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f;
      paint.setStyle(Paint.Style.FILL);paint.setColor(0xff11254b);canvas.drawRoundRect(0,0,w,h,dp(25),dp(25),paint);
      paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(4));paint.setColor(0xff33b8ff);
      canvas.drawRoundRect(dp(17),dp(21),w-dp(17),h-dp(27),dp(13),dp(13),paint);
      paint.setStyle(Paint.Style.FILL);paint.setColor(0xff4bdcff);
      Path triangle=new Path();triangle.moveTo(cx-dp(11),cy-dp(17));triangle.lineTo(cx+dp(21),cy);triangle.lineTo(cx-dp(11),cy+dp(17));triangle.close();canvas.drawPath(triangle,paint);
      paint.setColor(0xff90aee0);canvas.drawRoundRect(cx-dp(15),h-dp(18),cx+dp(15),h-dp(14),dp(2),dp(2),paint);
    }};
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(106),dp(106));lp.gravity=Gravity.CENTER;lp.bottomMargin=dp(12);mark.setLayoutParams(lp);return mark;
  }
  private TextView eyebrow(String label){TextView t=text(label,12);t.setLetterSpacing(.14f);t.setTypeface(null,Typeface.BOLD);t.setTextColor(0xff67d7ff);return t;}
  private void section(String title,String subtitle){TextView t=text(title,23);t.setTypeface(null,Typeface.BOLD);root.addView(t);TextView hint=text(subtitle,13);hint.setTextColor(0xffa2b5d5);root.addView(hint);}
  private void navItem(LinearLayout nav,String symbol,String label,Runnable action,boolean selected){
    LinearLayout item=vertical();item.setGravity(Gravity.CENTER);TextView glyph=centered(symbol,23,selected?0xff43caff:0xff9daecb);glyph.setPadding(0,dp(3),0,0);item.addView(glyph);
    TextView name=centered(label,11,selected?Color.WHITE:0xff94a7c6);name.setPadding(0,0,0,dp(4));item.addView(name);
    item.setOnClickListener(v->safeAction(action));nav.addView(item,new LinearLayout.LayoutParams(0,dp(48),1));
  }
  private void bottomNav(){LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);nav.setBackground(shape(0xff080e1c,dp(12),0xff1b2b44));
    navItem(nav,"⌂","Accueil",this::home,true);navItem(nav,"▣","Live TV",()->categories("live"),false);
    navItem(nav,"▣","Films",()->categories("vod"),false);navItem(nav,"◎","Séries",()->categories("series"),false);
    navItem(nav,"♙","Profil",this::showProfile,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.topMargin=dp(2);root.addView(nav,lp);
  }
  private void premiumBase(){stopDownloadRefresh();screenToken++;requestGeneration++;playbackScreen=false;root=vertical();root.setBackgroundColor(BG);root.setPadding(dp(12),dp(5),dp(12),dp(6));ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(BG);sc.addView(root);setContentView(sc);}
  private TextView iconLabel(String glyph,String title,int accent){
    TextView t=centered(glyph+"\n"+title,12,Color.WHITE);
    t.setLineSpacing(dp(5),1f);t.setBackground(gradient(accent,0xff0c1426,16));
    t.setPadding(dp(4),dp(12),dp(4),dp(12));return t;
  }
  private void login(){
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    loginPending=false;requestGeneration++;filterGeneration++;release();currentMediaKey="";activeStreamUrl="";resumeOnForeground=false;authenticated=false;username="";password="";backAction=null;
    stopDownloadRefresh();stopHeroRotation();screenToken++;requestGeneration++;playbackScreen=false;
    root=vertical();root.setOrientation(LinearLayout.HORIZONTAL);root.setGravity(Gravity.CENTER_VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(12),dp(8),dp(12),dp(8));setContentView(root);
    LinearLayout identity=vertical();identity.setGravity(Gravity.CENTER);identity.setPadding(dp(8),dp(5),dp(8),dp(5));
    identity.setBackground(gradient(0xff071e40,0xff120d2b,25));root.addView(identity,new LinearLayout.LayoutParams(0,-1,45));
    LinearLayout fields=vertical();fields.setPadding(dp(18),dp(2),dp(10),0);root.addView(fields,new LinearLayout.LayoutParams(0,-1,55));
    LinearLayout.LayoutParams markLp=new LinearLayout.LayoutParams(dp(76),dp(76));identity.addView(logoMark(),markLp);
    TextView brand=centered("IPTV PRO2",21,Color.WHITE);brand.setTypeface(null,Typeface.BOLD);identity.addView(brand);
    TextView tagline=centered("Votre divertissement, simplement.",12,0xffd8e6ff);identity.addView(tagline);
    addSpace(fields,5);
    EditText user=new EditText(this);user.setSingleLine(true);user.setHint("♙   Code utilisateur");user.setTextSize(15);user.setTextColor(Color.WHITE);user.setHintTextColor(0xffb2c0d8);user.setPadding(dp(18),0,dp(16),0);user.setBackground(shape(0xff0c1425,dp(16),0xff637493));fields.addView(user,new LinearLayout.LayoutParams(-1,dp(39)));user.setText(getSharedPreferences("login_prefs",MODE_PRIVATE).getString("last_user",""));
    addSpace(fields,7);
    LinearLayout passwordRow=new LinearLayout(this);passwordRow.setGravity(Gravity.CENTER_VERTICAL);passwordRow.setBackground(shape(0xff0c1425,dp(16),0xff637493));fields.addView(passwordRow,new LinearLayout.LayoutParams(-1,dp(39)));
    EditText pass=new EditText(this);pass.setSingleLine(true);pass.setHint("♙   Mot de passe");pass.setTextSize(15);pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pass.setTextColor(Color.WHITE);pass.setHintTextColor(0xffb2c0d8);pass.setPadding(dp(18),0,0,0);pass.setBackgroundColor(Color.TRANSPARENT);passwordRow.addView(pass,new LinearLayout.LayoutParams(0,-1,1));
    TextView eye=centered("◉",21,0xffb6c8e6);eye.setPadding(dp(12),0,dp(16),0);passwordRow.addView(eye);eye.setOnClickListener(v->{boolean visible=(pass.getInputType()&InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)!=0;pass.setInputType(InputType.TYPE_CLASS_TEXT|(visible?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));pass.setSelection(pass.length());});
    addSpace(fields,8);
    Button connect=button("▶   SE CONNECTER",()->{String u=user.getText().toString().trim(),pw=pass.getText().toString();if(u.isEmpty()||pw.isEmpty()){toast("Saisissez vos identifiants");return;}submitLogin(u,pw);});connect.setBackground(gradient(0xff03b8f4,0xff1249e9,18));connect.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(39)));fields.addView(connect);
    addSpace(fields,2);
    android.widget.CheckBox remember=new android.widget.CheckBox(this);remember.setText("Se souvenir de moi");remember.setTextColor(0xffd2dcf1);remember.setTextSize(13);remember.setChecked(getSharedPreferences("login_prefs",MODE_PRIVATE).getBoolean("remember",true));fields.addView(remember);
    rememberCredentials=remember.isChecked();remember.setOnCheckedChangeListener((v,checked)->{rememberCredentials=checked;if(!checked)getSharedPreferences("login_prefs",MODE_PRIVATE).edit().remove("last_pass").putBoolean("remember",false).apply();});
    if(remember.isChecked())pass.setText(getSharedPreferences("login_prefs",MODE_PRIVATE).getString("last_pass",""));
    addSpace(fields,3);
    LinearLayout features=new LinearLayout(this);features.setGravity(Gravity.CENTER_VERTICAL);fields.addView(features,new LinearLayout.LayoutParams(-1,dp(38)));
    String[] icons={"▣","▤","◉","4K"};String[] labels={"Live TV","Films","Séries","UHD"};int[] colors={0xff083d69,0xff133b66,0xff292064,0xff5a430c};
    for(int i=0;i<4;i++){TextView t=iconLabel(icons[i],labels[i],colors[i]);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1);lp.setMargins(dp(3),0,dp(3),0);features.addView(t,lp);}
  }
  private void submitLogin(String u,String p){submitLogin(u,p,false);}
  private void submitLogin(String u,String p,boolean automatic){
    if(loginPending||SystemClock.elapsedRealtime()-lastLoginAttempt<900)return;
    lastLoginAttempt=SystemClock.elapsedRealtime();loginPending=true;
    request("/player_api.php?username="+enc(u)+"&password="+enc(p),data->{
      loginPending=false;
      JSONObject auth=data.optJSONObject("user_info");
      boolean valid=auth!=null&&auth.optInt("auth",0)==1;
      String status=auth==null?"":auth.optString("status","");
      if(valid&&!"Disabled".equalsIgnoreCase(status)&&!"Banned".equalsIgnoreCase(status)&&!"Expired".equalsIgnoreCase(status)){
        if(!u.equals(getSharedPreferences("login_prefs",MODE_PRIVATE).getString("last_user","")))catalogCache().edit().clear().apply();
        username=u;password=p;
        getSharedPreferences("login_prefs",MODE_PRIVATE).edit()
          .putString("last_user",u).putBoolean("remember",rememberCredentials)
          .putString("last_pass",rememberCredentials?p:"").apply();
        authenticated=true;home();
      }else{
        if(automatic){
          getSharedPreferences("login_prefs",MODE_PRIVATE).edit().remove("last_pass").apply();
          login();
        }
        toast("Connexion refusée ou compte inactif");
      }
    },()->{
      loginPending=false;
      if(automatic){login();toast("Connexion automatique indisponible");}
    });
  }
  private void verifySavedLogin(String u,String p){
    // Verification must never put a splash screen over the restored home.
    try{networkExecutor.execute(()->{
      try{
        HttpURLConnection c=(HttpURLConnection)new URL(SERVER+"/player_api.php?username="+enc(u)+"&password="+enc(p)).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(10000);
        try{
          if(c.getResponseCode()!=200)return; // Network/server errors are not invalid credentials.
          ByteArrayOutputStream bytes=new ByteArrayOutputStream();
          try(InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1&&bytes.size()<131072)bytes.write(buf,0,n);}
          JSONObject response=new JSONObject(bytes.toString("UTF-8"));
          JSONObject info=response.optJSONObject("user_info");
          if(info==null)return;
          String status=info.optString("status","");
          boolean rejected=info.optInt("auth",1)==0||"Disabled".equalsIgnoreCase(status)||"Banned".equalsIgnoreCase(status)||"Expired".equalsIgnoreCase(status);
          if(rejected)runOnUiThread(()->{
            if(!authenticated||!u.equals(username)||!p.equals(password))return;
            getSharedPreferences("login_prefs",MODE_PRIVATE).edit().remove("last_pass").apply();
            login();toast("Session expirée : reconnectez-vous");
          });
        }finally{c.disconnect();}
      }catch(Exception e){android.util.Log.w("IPTV_PRO2","Background session verification unavailable",e);}
    });}catch(RejectedExecutionException ignored){}
  }
  private SharedPreferences catalogCache(){return getSharedPreferences("home_catalog_cache",MODE_PRIVATE);}
  private JSONObject cachedCatalog(String action){
    try{String raw=catalogCache().getString(action,"");if(raw.isEmpty())return null;
      JSONObject result=new JSONObject();result.put("items",new JSONArray(raw));return result;
    }catch(Exception e){return null;}
  }
  private void saveCatalog(String action,JSONObject data){
    try{JSONArray entries=array(data);if(entries.length()>0)catalogCache().edit().putString(action,entries.toString()).apply();}catch(Exception ignored){}
  }
  private boolean guard(){if(!authenticated||username.isEmpty()||password.isEmpty()){login();return false;}return true;}
  private void tile(LinearLayout parent,String glyph,String title,String subtitle,int a,int b,Runnable action){
    LinearLayout tile=vertical();tile.setPadding(dp(15),dp(19),dp(12),dp(18));tile.setBackground(gradient(a,b,20));
    TextView symbol=text(glyph,29);tile.addView(symbol);TextView heading=text(title,17);heading.setTypeface(null,Typeface.BOLD);tile.addView(heading);TextView info=text(subtitle,12);info.setTextColor(0xffd0d8ed);tile.addView(info);
    tile.setOnClickListener(v->safeAction(action));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(160),1);lp.setMargins(dp(3),0,dp(3),0);parent.addView(tile,lp);
  }
  private TextView compactAction(String label,Runnable action){
    TextView t=centered(label,13,Color.WHITE);t.setTypeface(null,Typeface.BOLD);t.setPadding(dp(13),dp(10),dp(13),dp(10));
    t.setBackground(gradient(0xff075bbf,0xff7828c5,13));t.setOnClickListener(v->safeAction(action));return t;
  }
  private View cinematicHeroArt(){
    return new View(this){final Paint p=new Paint(3);@Override protected void onDraw(Canvas c){
      super.onDraw(c);float w=getWidth(),h=getHeight();
      p.setShader(new android.graphics.LinearGradient(0,0,w,h,0xff071326,0xff263a87,android.graphics.Shader.TileMode.CLAMP));c.drawRoundRect(0,0,w,h,dp(18),dp(18),p);p.setShader(null);
      p.setColor(0x88523ac6);c.drawCircle(w*.85f,h*.35f,h*.52f,p);
      p.setColor(0x7733c8ff);c.drawCircle(w*.77f,h*.27f,h*.27f,p);
      p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(4));p.setColor(0xffa4dfff);c.drawCircle(w*.77f,h*.46f,h*.24f,p);p.setStyle(Paint.Style.FILL);
      p.setColor(0xffeaf6ff);Path triangle=new Path();triangle.moveTo(w*.72f,h*.31f);triangle.lineTo(w*.72f,h*.61f);triangle.lineTo(w*.88f,h*.46f);triangle.close();c.drawPath(triangle,p);
      p.setColor(0xffedfcff);p.setTextSize(dp(13));p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));c.drawText("CINÉMA • LIVE • SÉRIES",dp(18),dp(29),p);
      p.setTextSize(dp(25));c.drawText("Votre univers",dp(18),h*.52f,p);c.drawText("streaming",dp(18),h*.70f,p);
    }};
  }
  private void home(){release();if(!guard())return;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);stopHeroRotation();stopDownloadRefresh();screenToken++;requestGeneration++;playbackScreen=false;root=vertical();root.setBackgroundColor(BG);root.setPadding(dp(13),0,dp(13),0);setContentView(root);backAction=null;
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
    TextView brand=text("▣ IPTV PRO2",21);brand.setTypeface(null,Typeface.BOLD);brand.setTextColor(0xff50caff);brand.setPadding(dp(4),dp(2),0,dp(2));top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
    TextView searchIcon=centered("⌕",28,Color.WHITE);top.addView(searchIcon);searchIcon.setOnClickListener(v->showSearchHub());
    TextView settings=centered("⚙",22,Color.WHITE);top.addView(settings);settings.setOnClickListener(v->showProfile());
    addSpace(root,2);
    android.widget.FrameLayout heroFrame=new android.widget.FrameLayout(this);root.addView(heroFrame,new LinearLayout.LayoutParams(-1,0,38));heroFrame.setBackground(shape(0xff0c182b,dp(15),0));
    ImageView heroPoster=new ImageView(this);heroPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
    heroFrame.addView(heroPoster,new android.widget.FrameLayout.LayoutParams(-1,-1));
    View heroFallback=cinematicHeroArt();heroFrame.addView(heroFallback,new android.widget.FrameLayout.LayoutParams(-1,-1));heroFallback.setVisibility(View.VISIBLE);
    // The artwork view becomes visible as soon as the catalog returns actual posters.
    View tint=new View(this);tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xee030914,0x99030914,0x18030914}));
    heroFrame.addView(tint,new android.widget.FrameLayout.LayoutParams(-1,-1));
    LinearLayout heroCaption=vertical();heroCaption.setPadding(dp(18),dp(4),dp(10),dp(10));
    android.widget.FrameLayout.LayoutParams captionLp=new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
    heroFrame.addView(heroCaption,captionLp);
    TextView heroTitle=text("Films récents",23);heroTitle.setTypeface(null,Typeface.BOLD);heroTitle.setMaxLines(2);heroTitle.setPadding(0,0,0,dp(4));heroCaption.addView(heroTitle);
    LinearLayout heroButtons=new LinearLayout(this);heroButtons.setGravity(Gravity.CENTER_VERTICAL);heroCaption.addView(heroButtons);
    TextView watch=compactAction("▶  Regarder",()->categories("vod"));heroButtons.addView(watch,new LinearLayout.LayoutParams(dp(146),dp(37)));
    TextView info=centered("Plus d’infos",14,Color.WHITE);info.setTypeface(null,Typeface.BOLD);info.setBackground(gradient(0xff354052,0xff192435,12));
    LinearLayout.LayoutParams infoLp=new LinearLayout.LayoutParams(dp(132),dp(37));infoLp.leftMargin=dp(9);heroButtons.addView(info,infoLp);
    info.setOnClickListener(v->categories("vod"));
    final int heroToken=screenToken;
    Result renderHero=data->{
      if(heroToken!=screenToken||!authenticated)return;
      JSONArray catalog=array(data);ArrayList<JSONObject> movies=new ArrayList<>();
      for(int i=catalog.length()-1;i>=0&&movies.size()<12;i--){JSONObject item=catalog.optJSONObject(i);if(item==null)continue;
        String poster=item.optString("stream_icon",item.optString("cover",item.optString("cover_big","")));
        if(poster.startsWith("http://")||poster.startsWith("https://"))movies.add(item);
      }
      if(movies.isEmpty()){heroTitle.setText("Aucune affiche disponible • Voir les films");return;}
      heroFallback.setVisibility(View.GONE); // Keep the image behind the overlay and controls.
      // One featured movie only: no timer, carousel or automatic title/poster changes.
      // Prefer a landscape backdrop when the Xtream catalog provides one.
      JSONObject movie=movies.get(0);
      String poster=movie.optString("backdrop_path","");
      if(poster.startsWith("[")){try{poster=new JSONArray(poster).optString(0,"");}catch(Exception ignored){poster="";}}
      if(!poster.startsWith("http"))poster=movie.optString("stream_icon",movie.optString("cover",movie.optString("cover_big","")));
      heroTitle.setText(movie.optString("name","Film récent"));
      loadArtwork(heroPoster,poster);
      watch.setOnClickListener(v->{currentType="vod";currentMediaTitle=movie.optString("name","Film");play(movie.optString("stream_id"),"vod",movie.optString("container_extension","mp4"),MainActivity.this::home);});
      info.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle(movie.optString("name","Film"))
        .setMessage("Année : "+movie.optString("year","—")+"\nNote : "+movie.optString("rating","—")+"\n"+movie.optString("plot",movie.optString("description","Informations non disponibles")))
        .setPositiveButton("Regarder",(dialog,which)->watch.performClick()).setNegativeButton("Fermer",null).show());
    };
    JSONObject oldHero=cachedCatalog("get_vod_streams");if(oldHero!=null)renderHero.done(oldHero);
    request(api("get_vod_streams"),data->{saveCatalog("get_vod_streams",data);renderHero.done(data);},()->{});
    addSpace(root,2);
    LinearLayout shortcuts=new LinearLayout(this);shortcuts.setOrientation(0);root.addView(shortcuts,new LinearLayout.LayoutParams(-1,0,15));
    homeShortcut(shortcuts,"▣","Live TV",0xff084c82,()->categories("live"));
    homeShortcut(shortcuts,"▤","Films",0xff8a163b,()->categories("vod"));
    homeShortcut(shortcuts,"◉","Séries",0xff552293,()->categories("series"));
    homeShortcut(shortcuts,"☆","Favoris",0xff86550b,this::showFavorites);
    addSpace(root,2);
    LinearLayout shelves=new LinearLayout(this);shelves.setOrientation(LinearLayout.VERTICAL);
    root.addView(shelves,new LinearLayout.LayoutParams(-1,0,47));
    homeShelf(shelves,"Chaînes en direct","live","get_live_streams");
    homeShelf(shelves,"Films populaires","vod","get_vod_streams");
    bottomNav();
  }
  private void homeShortcut(LinearLayout parent,String icon,String name,int color,Runnable action){
    LinearLayout card=vertical();card.setGravity(Gravity.CENTER);card.setPadding(dp(2),dp(1),dp(2),dp(1));card.setBackground(gradient(color,0xff10192b,14));
    LinearLayout content=new LinearLayout(this);content.setGravity(Gravity.CENTER);card.addView(content,new LinearLayout.LayoutParams(-1,-1));
    TextView glyph=centered(icon,27,0xffa5e8ff);content.addView(glyph);
    TextView label=centered(name,17,Color.WHITE);label.setTypeface(null,Typeface.BOLD);label.setPadding(dp(13),0,0,0);content.addView(label);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(3),0,dp(3),0);parent.addView(card,lp);card.setOnClickListener(v->safeAction(action));
  }
  private void homeShelf(LinearLayout shelves,String title,String type,String action){
    LinearLayout shelf=vertical();LinearLayout.LayoutParams shelfLp=new LinearLayout.LayoutParams(-1,0,1);shelfLp.setMargins(dp(3),dp(2),dp(3),dp(2));shelves.addView(shelf,shelfLp);
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);shelf.addView(heading);
    TextView label=text(title,17);label.setTypeface(null,Typeface.BOLD);label.setPadding(dp(3),dp(2),0,dp(2));heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    TextView all=text("Voir tout  ›",13);all.setTextColor(0xff9aaed3);heading.addView(all);all.setOnClickListener(v->categories(type));
    HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.setHorizontalScrollBarEnabled(false);shelf.addView(horizontal,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout row=new LinearLayout(this);row.setOrientation(0);horizontal.addView(row);
    final int token=screenToken;
    Result renderShelf=data->{if(token!=screenToken||!authenticated)return;row.removeAllViews();JSONArray entries=array(data);int count=Math.min(entries.length(),14);
      for(int i=0;i<count;i++){JSONObject item=entries.optJSONObject(i);if(item==null)continue;
        String name=item.optString("name","Sans titre");String url=item.optString("stream_icon",item.optString("cover",item.optString("cover_big","")));
        LinearLayout card=vertical();card.setBackground(shape(0xff111e34,dp(12),0xff263856));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(type.equals("live")?166:218),-1);cp.rightMargin=dp(9);row.addView(card,cp);
        ImageView poster=new ImageView(this);poster.setScaleType(type.equals("live")?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);poster.setBackground(gradient(0xff1c3b68,0xff321947,12));
        card.addView(poster,new LinearLayout.LayoutParams(-1,0,1));
        if(url.startsWith("http://")||url.startsWith("https://"))loadArtwork(poster,url);
        // Reference design: artwork-only tiles, with the full title available on long press.
        card.setContentDescription(name);
        card.setOnLongClickListener(v->{toast(name);return true;});
        card.setOnClickListener(v->{if(type.equals("series")){currentType="series";currentCategory="";currentSeries=item.optString("series_id");series(currentSeries);}else{currentType=type;currentMediaTitle=name;play(item.optString("stream_id"),type,item.optString("container_extension",type.equals("live")?"m3u8":"mp4"),this::home);}});
      }
      if(row.getChildCount()==0)row.addView(text("Aucun contenu disponible",12));
    };
    JSONObject oldShelf=cachedCatalog(action);if(oldShelf!=null)renderShelf.done(oldShelf);
    request(api(action),data->{saveCatalog(action,data);renderShelf.done(data);},()->{});
  }
  private void showProfile(){
    if(!guard())return;screen("Mon profil");backAction=this::home;
    root.addView(button("‹ Retour",this::home));root.addView(text("Utilisateur : "+username,16));
    root.addView(button("Mes téléchargements",this::showDownloads));
    root.addView(compactAction("Déconnexion",()->{release();authenticated=false;username="";password="";currentList=null;getSharedPreferences("login_prefs",MODE_PRIVATE).edit().remove("last_pass").putBoolean("remember",false).apply();catalogCache().edit().clear().apply();login();}));
  }
  private String api(String action){return "/player_api.php?username="+enc(username)+"&password="+enc(password)+"&action="+action;}
  private void showSearchHub(){
    if(!guard())return;
    screen("Recherche IPTV PRO2");backAction=this::home;
    root.addView(text("Choisissez le catalogue à rechercher",16));
    root.addView(button("⌕  Rechercher une chaîne",()->categories("live")));
    root.addView(button("⌕  Rechercher un film",()->categories("vod")));
    root.addView(button("⌕  Rechercher une série",()->categories("series")));
    root.addView(compactAction("‹ Retour à l'accueil",this::home));
  }
  private void categories(String type){if(!guard())return;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);currentType=type;String action=type.equals("live")?"get_live_categories":type.equals("vod")?"get_vod_categories":"get_series_categories";request(api(action),data->{cachedCategories.put(type,array(data));showList("Catégories",array(data),"category_name",this::home,item->items(type,item.optString("category_id")));loadCategoryPosters(type);});}
  private void items(String type,String category){if(!guard())return;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);currentType=type;currentCategory=category;String action=type.equals("live")?"get_live_streams":type.equals("vod")?"get_vod_streams":"get_series";request(api(action)+"&category_id="+enc(category),data->showList("Catalogue",array(data),"name",()->categories(type),item->{if(type.equals("series")){currentSeries=item.optString("series_id");series(currentSeries);}else{String id=item.optString("stream_id");currentMediaTitle=item.optString("name", "Vidéo");play(id,type,item.optString("container_extension",type.equals("live")?"m3u8":"mp4"),()->renderList(listFilter));}}));}
  private void series(String id){if(!guard())return;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);request(api("get_series_info")+"&series_id="+enc(id),data->{JSONObject seasons=data.optJSONObject("episodes");JSONArray all=new JSONArray();if(seasons!=null){List<String> keys=new ArrayList<>();Iterator<String> it=seasons.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys,(a,b)->{try{return Integer.compare(Integer.parseInt(a),Integer.parseInt(b));}catch(Exception e){return a.compareTo(b);}});for(String key:keys){JSONArray eps=seasons.optJSONArray(key);if(eps!=null)for(int i=0;i<eps.length();i++){JSONObject ep=eps.optJSONObject(i);if(ep!=null)all.put(ep);}}}showList("Épisodes",all,"title",()->items("series",currentCategory),item->{JSONObject info=item.optJSONObject("info");String ext=item.optString("container_extension",info!=null?info.optString("container_extension","mp4"):"mp4");currentMediaTitle=item.optString("title", "Épisode");play(item.optString("id"),"series",ext,()->renderList(listFilter));});});}
  private JSONArray array(JSONObject data){JSONArray a=data.optJSONArray("items");return a==null?new JSONArray():a;}
  interface Pick{void chosen(JSONObject item);}
  private void showList(String title,JSONArray data,String key,Runnable back,Pick pick){if(!guard())return;currentListTitle=title;currentList=data;currentListKey=key;currentListBack=back;currentPick=pick;listPage=0;listFilter="";renderList("");}
  private void renderList(String filter){
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    if(pendingSearch!=null){searchHandler.removeCallbacks(pendingSearch);pendingSearch=null;}
    if(!guard())return;
    listFilter=filter;filterGeneration++;screen(currentListTitle);backAction=currentListBack;
    // Landscape split view: persistent category navigation at left, catalogue at right.
    LinearLayout shell=root;
    shell.removeAllViews();shell.setPadding(dp(8),dp(4),dp(8),dp(4));shell.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout sidebar=vertical();sidebar.setPadding(dp(8),dp(8),dp(8),dp(8));
    sidebar.setBackground(shape(0xff0c0920,dp(10),0xff7436b5));
    ScrollView sidebarScroll=new ScrollView(this);sidebarScroll.setFillViewport(false);sidebarScroll.addView(sidebar);
    int sidebarWidth=Math.min(dp(220),Math.max(dp(140),getResources().getDisplayMetrics().widthPixels/4));
    shell.addView(sidebarScroll,new LinearLayout.LayoutParams(sidebarWidth,-1));
    LinearLayout content=vertical();content.setPadding(dp(14),0,0,0);
    shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
    TextView brand=centered("▣  IPTV PRO2",17,0xff77c8ff);brand.setTypeface(null,Typeface.BOLD);sidebar.addView(brand);
    addSidebarAction(sidebar,"‹  RETOUR",currentListBack!=null?currentListBack:this::home,false);
    addSidebarAction(sidebar,"⌂  ACCUEIL",this::home,false);
    addSidebarAction(sidebar,"▣  LIVE TV",()->categories("live"),"live".equals(currentType));
    addSidebarAction(sidebar,"▣  FILMS",()->categories("vod"),"vod".equals(currentType));
    addSidebarAction(sidebar,"◉  SÉRIES",()->categories("series"),"series".equals(currentType));
    addSidebarAction(sidebar,"☆  FAVORIS",this::showFavorites,false);
    addSidebarAction(sidebar,"⇩  TÉLÉCHARGEMENTS",this::showDownloads,false);
    View divider=new View(this);divider.setBackgroundColor(0xff35405d);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,dp(1));dl.setMargins(0,dp(9),0,dp(9));sidebar.addView(divider,dl);
    final String sidebarType=currentType;
    JSONArray categoryData=cachedCategories.get(sidebarType);
    if(categoryData!=null){
      for(int i=0;i<categoryData.length();i++){
        JSONObject category=categoryData.optJSONObject(i);if(category==null)continue;
        String id=category.optString("category_id");String name=category.optString("category_name","Catégorie");
        boolean selected=id.equals(currentCategory)&&!"Catégories".equals(currentListTitle);
        addSidebarAction(sidebar,name,()->{currentType=sidebarType;items(sidebarType,id);},selected);
      }
    }
    LinearLayout headingRow=new LinearLayout(this);headingRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=text(currentListTitle,21);heading.setTypeface(null,Typeface.BOLD);
    headingRow.addView(heading,new LinearLayout.LayoutParams(0,dp(34),1));
    TextView back=compactAction("‹ Retour",currentListBack);headingRow.addView(back,new LinearLayout.LayoutParams(dp(92),dp(32)));
    content.addView(headingRow);
    EditText search=input("⌕  Rechercher dans "+currentListTitle.toLowerCase(Locale.ROOT)+"...",false);
    search.setTextSize(13);search.setPadding(dp(10),dp(5),dp(10),dp(5));
    content.addView(search,new LinearLayout.LayoutParams(-1,dp(34)));
    ScrollView scroll=new ScrollView(this);LinearLayout list=vertical();scroll.addView(list);
    content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    search.setText(filter);
    search.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence s,int st,int c,int a){}
      public void onTextChanged(CharSequence s,int st,int before,int count){
        if(pendingSearch!=null)searchHandler.removeCallbacks(pendingSearch);
        String q=s.toString();listFilter=q;pendingSearch=()->{listPage=0;populate(list,q);};
        searchHandler.postDelayed(pendingSearch,220);
      }
      public void afterTextChanged(android.text.Editable e){}
    });
    populate(list,filter);
  }
  private void addSidebarAction(LinearLayout sidebar,String label,Runnable action,boolean selected){
    TextView item=text(label,13);item.setSingleLine(true);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
    item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(13),dp(10),dp(8),dp(10));
    item.setTextColor(selected?Color.WHITE:0xffd0d9ef);
    item.setBackground(shape(selected?0xff6c2bc2:0xff0c0920,dp(8),selected?0xffb86cff:0));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(35));lp.setMargins(0,dp(1),0,dp(1));sidebar.addView(item,lp);
    item.setOnClickListener(v->safeAction(action));
  }
  private void populate(LinearLayout list,String filter){
    final int generation=++filterGeneration;
    final JSONArray data=currentList;
    final String key=currentListKey;
    final String needle=filter.toLowerCase(Locale.ROOT);
    list.removeAllViews();list.addView(text("Recherche…",14));
    filterExecutor.getQueue().clear();filterExecutor.execute(()->{
      ArrayList<JSONObject> matches=new ArrayList<>();
      if(data!=null)for(int j=0;j<data.length();j++){
        JSONObject item=data.optJSONObject(j);
        if(item!=null&&item.optString(key,"Sans titre").toLowerCase(Locale.ROOT).contains(needle))matches.add(item);
      }
      runOnUiThread(()->{if(generation!=filterGeneration||!authenticated)return;renderMatches(list,filter,matches);});
    });
  }
  private void renderMatches(LinearLayout list,String filter,ArrayList<JSONObject> matches){
    list.removeAllViews();
    if("Catégories".equals(currentListTitle))categoryImageViews.clear();
    if(matches.isEmpty()){list.addView(text("Aucun résultat",16));return;}
    final boolean cats="Catégories".equals(currentListTitle);
    final boolean live="live".equals(currentType);
    final int columns=cats?4:(live?5:5);
    int totalPages=(matches.size()+PAGE_SIZE-1)/PAGE_SIZE;
    listPage=Math.max(0,Math.min(listPage,totalPages-1));
    TextView summary=text(matches.size()+" résultats  •  Page "+(listPage+1)+"/"+totalPages,13);
    summary.setTextColor(0xffaebbd4);summary.setPadding(dp(4),dp(3),0,dp(4));list.addView(summary);
    if(listPage>0)list.addView(compactAction("‹  Page précédente",()->{listPage--;renderMatches(list,filter,matches);}));
    int end=Math.min(matches.size(),(listPage+1)*PAGE_SIZE);
    LinearLayout gridRow=null;
    for(int j=listPage*PAGE_SIZE;j<end;j++){
      if((j-listPage*PAGE_SIZE)%columns==0){gridRow=new LinearLayout(this);gridRow.setOrientation(LinearLayout.HORIZONTAL);list.addView(gridRow,new LinearLayout.LayoutParams(-1,-2));}
      JSONObject item=matches.get(j);
      LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(dp(3),dp(4),dp(3),dp(3));
      card.setBackground(shape(0xff17102f,dp(9),0xff54318d));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));gridRow.addView(card,lp);
      String imageUrl=item.optString("stream_icon",item.optString("cover",item.optString("cover_big","")));
      ImageView art=new ImageView(this);
      art.setScaleType(live?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
      art.setBackground(gradient(cats?0xff28204e:0xff142b4a,0xff0b1021,11));
      int usableHeight=getResources().getDisplayMetrics().heightPixels;
      int imageHeight=Math.max(dp(45),Math.min(dp(live?72:94),(usableHeight-dp(185))/3));
      LinearLayout.LayoutParams imageLp=new LinearLayout.LayoutParams(-1,imageHeight);
      card.addView(art,imageLp);
      if(cats){
        String catId=item.optString("category_id");
        categoryImageViews.computeIfAbsent(catId,k->new ArrayList<>()).add(art);
        String found=categoryPosters.get(currentType+":"+catId);
        if(found!=null)loadArtwork(art,found);
      }else if(imageUrl.startsWith("http://")||imageUrl.startsWith("https://"))loadArtwork(art,imageUrl);
      TextView name=text(item.optString(currentListKey,"Sans titre"),cats?13:12);
      name.setTypeface(null,Typeface.BOLD);name.setGravity(Gravity.CENTER);
      name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      name.setMinHeight(dp(26));name.setPadding(dp(1),dp(3),dp(1),dp(1));
      card.addView(name,new LinearLayout.LayoutParams(-1,-2));
      final Pick itemPick=currentPick;
      card.setOnClickListener(v->safeAction(()->{if(itemPick!=null)itemPick.chosen(item);}));
      if(!cats){
        String favType=currentType;String favName=item.optString(currentListKey,"Sans titre");
        String favId=item.optString("stream_id",item.optString("series_id",item.optString("id","")));
        String favExt=item.optString("container_extension","mp4");
        if(!favId.isEmpty()){
          TextView star=centered(isFavorite(favType,favId)?"★":"☆",19,0xffffd76b);
          star.setContentDescription("Ajouter ou retirer des favoris");
          card.addView(star,new LinearLayout.LayoutParams(-1,dp(23)));
          star.setOnClickListener(v->{toggleFavorite(favType,favId,favName,favExt);star.setText(isFavorite(favType,favId)?"★":"☆");});
        }
      }
    }
    if(listPage+1<totalPages)list.addView(compactAction("Page suivante  ›",()->{listPage++;renderMatches(list,filter,matches);}));
  }
  private void loadCategoryPosters(String type){
    if("live".equals(type)||!categoryPosterRequests.add(type))return;
    // Category endpoints have no poster field. Use the first real catalog poster per category.
    String action="vod".equals(type)?"get_vod_streams":"get_series";
    final int ownerToken=screenToken;
    try{networkExecutor.execute(()->{
      HttpURLConnection conn=null;
      try{
        conn=(HttpURLConnection)new URL(SERVER+api(action)).openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept","application/json");
        try(InputStream input=conn.getInputStream()){
          ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
          while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>32_000_000)throw new IOException("Catalog too large");}
          String raw=bytes.toString("UTF-8").trim();
          JSONArray entries=raw.startsWith("[")?new JSONArray(raw):new JSONObject(raw).optJSONArray("items");
          if(entries!=null)for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;
            String id=entry.optString("category_id");
            String poster=entry.optString("cover",entry.optString("stream_icon",entry.optString("cover_big","")));
            if(!id.isEmpty()&&(poster.startsWith("http://")||poster.startsWith("https://")))categoryPosters.putIfAbsent(type+":"+id,poster);
          }
        }
        runOnUiThread(()->{
          if(ownerToken!=screenToken||!"Catégories".equals(currentListTitle)||!type.equals(currentType))return;
          for(Map.Entry<String,ArrayList<ImageView>> entry:categoryImageViews.entrySet()){
            String poster=categoryPosters.get(type+":"+entry.getKey());
            if(poster!=null)for(ImageView view:entry.getValue())loadArtwork(view,poster);
          }
        });
      }catch(Exception ignored){categoryPosterRequests.remove(type);}
      finally{if(conn!=null)conn.disconnect();}
    });}catch(RejectedExecutionException ignored){categoryPosterRequests.remove(type);}
  }
  private void loadArtwork(ImageView target,String url){
    target.setTag(url);
    target.setImageDrawable(null);
    Bitmap cached=artworkCache.get(url);if(cached!=null){target.setImageBitmap(cached);return;}
    artworkExecutor.execute(()->{
      Bitmap bitmap=null;
      try{
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(5000);conn.setReadTimeout(7000);conn.setInstanceFollowRedirects(true);
        try(InputStream input=conn.getInputStream()){
          BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=2;
          bitmap=BitmapFactory.decodeStream(input,null,opts);
        }finally{conn.disconnect();}
      }catch(Exception ignored){}
      if(bitmap!=null){artworkCache.put(url,bitmap);final Bitmap ready=bitmap;
        runOnUiThread(()->{if(url.equals(target.getTag()) && target.isAttachedToWindow())target.setImageBitmap(ready);});}
    });
  }
  private String cleanExt(String ext,String type){String value=ext==null?"":ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");if(value.isEmpty())return type.equals("live")?"m3u8":"mp4";return value;}
  private void play(String id,String type,String extension,Runnable back){
    if(!guard())return;if(id.isEmpty()){toast("Flux indisponible");return;}
    release();String folder=type.equals("live")?"live":type.equals("vod")?"movie":"series";
    String suffix=cleanExt(extension,type);
    currentStreamId=id;currentStreamType=type;currentStreamExt=suffix;
    currentMediaType=type;currentMediaKey=type+":"+id;fallbackAttempts=0;fallbackBudget=0;resumeApplied=false;playbackGeneration++;
    alternatives=type.equals("live") ? (suffix.equals("m3u8")?new String[]{"m3u8","ts"}:new String[]{suffix,"m3u8","ts"}) : new String[]{suffix};
    alternateIndex=0;
    activeStreamUrl=streamUrl(folder,id,alternatives[alternateIndex]);
    // One-screen landscape player: video gets the remaining height; controls never push it off-screen.
    screen("IPTV PRO2 • Player");playbackScreen=true;resumeOnForeground=true;
    playerScreenRoot=root;
    activePlayerHeading=(TextView)root.getChildAt(0);
    activePlayerHeading.setVisibility(View.GONE);
    lastPosition=type.equals("live")?0:positions().getLong(positionKey(),0);
    playerBack=back;
    backAction=()->{if(fullscreenPlayer){setPlayerFullscreen(false);return;}release();activeStreamUrl="";resumeOnForeground=false;back.run();};
    LinearLayout toolbar=new LinearLayout(this);toolbar.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(40)));
    Button goBack=button("‹ RETOUR",backAction);activePlayerBack=goBack;
    toolbar.addView(goBack,new LinearLayout.LayoutParams(0,dp(40),1));
    Button full=button("⛶ PLEIN ÉCRAN",()->setPlayerFullscreen(!fullscreenPlayer));
    toolbar.addView(full,new LinearLayout.LayoutParams(0,dp(40),1));
    TextView status=text("Chargement du flux…",12);playbackStatus=status;activePlayerStatus=status;
    status.setPadding(dp(6),0,dp(6),0);root.addView(status,new LinearLayout.LayoutParams(-1,dp(24)));
    android.widget.FrameLayout videoArea=new android.widget.FrameLayout(this);
    root.addView(videoArea,new LinearLayout.LayoutParams(-1,0,1));
    PlayerView view=new PlayerView(this);activePlayerView=view;
    videoArea.addView(view,new android.widget.FrameLayout.LayoutParams(-1,-1));
    // A visible back control stays on top of the video even in fullscreen.
    TextView overlayBack=centered("‹ RETOUR",13,Color.WHITE);
    overlayBack.setTypeface(null,Typeface.BOLD);
    overlayBack.setBackground(shape(0xcc10132c,dp(9),0xff7542ba));
    android.widget.FrameLayout.LayoutParams overlayParams=new android.widget.FrameLayout.LayoutParams(dp(94),dp(38),Gravity.TOP|Gravity.LEFT);
    overlayParams.setMargins(dp(8),dp(8),0,0);videoArea.addView(overlayBack,overlayParams);
    overlayBack.setOnClickListener(v->safeAction(backAction));
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(controls,new LinearLayout.LayoutParams(-1,dp(42)));
    if("live".equals(type)){
      Button epg=button("EPG",()->showEpg(id));controls.addView(epg,new LinearLayout.LayoutParams(0,dp(40),1));
    }else{
      Button download=button("↓ TÉLÉCHARGER",()->downloadVideo(id,type,suffix));controls.addView(download,new LinearLayout.LayoutParams(0,dp(40),1));
    }
    final Button favoriteButton=button(isFavorite(type,id)?"★ FAVORI":"☆ FAVORIS",()->{});
    favoriteButton.setOnClickListener(v->{toggleFavorite(type,id,currentMediaTitle,suffix);favoriteButton.setText(isFavorite(type,id)?"★ FAVORI":"☆ FAVORIS");toast("Favoris mis à jour");});
    controls.addView(favoriteButton,new LinearLayout.LayoutParams(0,dp(40),1));
    Button retry=button("↻ RÉESSAYER",()->restartPlayback(false));controls.addView(retry,new LinearLayout.LayoutParams(0,dp(40),1));
    if(type.equals("live")){Button alternate=button("FORMAT",()->restartPlayback(true));controls.addView(alternate,new LinearLayout.LayoutParams(0,dp(40),1));}
    activePlayerControls=null;
    player=new ExoPlayer.Builder(this).build();view.setPlayer(player);
    player.addListener(new Player.Listener(){
      @Override public void onPlaybackStateChanged(int state){
        if(state==Player.STATE_READY){status.setText("");if(!resumeApplied&&lastPosition>0&&!"live".equals(currentMediaType)){player.seekTo(lastPosition);lastPosition=0;resumeApplied=true;}}
        else if(state==Player.STATE_BUFFERING)status.setText("Chargement du flux…");
        else if(state==Player.STATE_ENDED){status.setText("Lecture terminée");if(!"live".equals(currentMediaType))positions().edit().remove(positionKey()).apply();}
      }
      @Override public void onPlayerError(PlaybackException error){
        if("live".equals(currentMediaType)&&fallbackBudget<alternatives.length-1){
          fallbackAttempts++;fallbackBudget++;status.setText("Flux indisponible. Essai automatique : "+alternatives[(alternateIndex+1)%alternatives.length]);
          final int generation=playbackGeneration;status.postDelayed(()->{if(player!=null&&playbackScreen&&generation==playbackGeneration)restartPlayback(true);},650);
        }else status.setText("Lecture impossible ("+error.getErrorCodeName()+"). Appuyez sur RÉESSAYER.");
      }
    });
    startMedia(suffix);
  }
  private void showEpg(String streamId){
    if(!guard())return;
    request(api("get_short_epg")+"&stream_id="+enc(streamId)+"&limit=12",data->{
      JSONArray entries=data.optJSONArray("epg_listings");
      if(entries==null||entries.length()==0){toast("Guide TV indisponible pour cette chaîne");return;}
      ScrollView sc=new ScrollView(this);
      LinearLayout rows=vertical();rows.setPadding(dp(12),dp(8),dp(12),dp(8));rows.setBackgroundColor(BG);sc.addView(rows);
      long now=System.currentTimeMillis()/1000L;
      for(int i=0;i<entries.length();i++){
        JSONObject e=entries.optJSONObject(i);if(e==null)continue;
        String title=decodeEpg(e.optString("title",""));
        String desc=decodeEpg(e.optString("description",""));
        String startTime=e.optString("start",""),endTime=e.optString("end","");
        long begin=e.optLong("start_timestamp",0),finish=e.optLong("stop_timestamp",0);
        boolean current=begin>0&&finish>begin&&now>=begin&&now<finish;
        LinearLayout card=vertical();card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackground(shape(current?0xff15395c:PANEL,dp(14),current?0xff2f9de4:0xff293a56));
        TextView time=text((current?"● EN CE MOMENT  •  ":"")+startTime+" — "+endTime,12);
        time.setTextColor(current?0xff68dcff:0xffa4b8d5);card.addView(time);
        TextView name=text(title.isEmpty()?"Programme sans titre":title,17);name.setTypeface(null,Typeface.BOLD);card.addView(name);
        if(current){ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
          bar.setMax(100);bar.setProgress((int)Math.max(0,Math.min(100,(now-begin)*100/(finish-begin))));card.addView(bar);}
        if(!desc.isEmpty()){TextView description=text(desc,13);description.setTextColor(0xffb5c4d9);card.addView(description);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(9);rows.addView(card,lp);
      }
      new android.app.AlertDialog.Builder(this).setTitle("Guide TV • EPG")
        .setView(sc).setPositiveButton("FERMER",(d,w)->d.dismiss()).show();
    });
  }
  private String decodeEpg(String value){
    if(value==null||value.isEmpty())return "";
    try{String decoded=new String(Base64.decode(value,Base64.DEFAULT),"UTF-8");
      if(decoded.indexOf('\uFFFD')<0)return decoded;
    }catch(Exception ignored){}
    return value;
  }
  private void downloadVideo(String id,String type,String extension){
    if(!guard()||"live".equals(type))return;
    String ext=cleanExt(extension,type);
    if(!Arrays.asList("mp4","mkv","avi","mov","ts","webm").contains(ext)){
      toast("Téléchargement direct indisponible pour ce format");return;
    }
    String folder="series".equals(type)?"series":"movie";
    String url=streamUrl(folder,id,ext);
    new android.app.AlertDialog.Builder(this).setTitle("Télécharger la vidéo")
      .setMessage("Enregistrer ce fichier dans Téléchargements ? Utilisez uniquement des contenus autorisés.")
      .setNegativeButton("ANNULER",null)
      .setPositiveButton("TÉLÉCHARGER",(dialog,which)->{
        try{
          DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
          req.setTitle("IPTV PRO2 • "+("series".equals(type)?"Épisode ":"Film ")+id);
          req.setDescription("Téléchargement en cours");
          req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
          req.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"IPTV_PRO2_"+type+"_"+id+"."+ext);
          DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
          if(dm==null)throw new IllegalStateException("Service de téléchargement indisponible");
          getSharedPreferences("downloads",MODE_PRIVATE).edit().putLong("id_"+type+"_"+id,dm.enqueue(req)).apply();
          toast("Téléchargement lancé • Consultez Mes téléchargements");
        }catch(Exception e){toast("Téléchargement impossible : "+e.getClass().getSimpleName());}
      }).show();
  }
  private SharedPreferences favorites(){return getSharedPreferences("favorites",MODE_PRIVATE);}
  private String favKey(String type,String id){return type+":"+id;}
  private boolean isFavorite(String type,String id){return favorites().contains(favKey(type,id));}
  private void toggleFavorite(String type,String id,String title,String ext){
    String key=favKey(type,id);
    if(isFavorite(type,id)){favorites().edit().remove(key).apply();return;}
    JSONObject obj=new JSONObject();try{obj.put("type",type);obj.put("id",id);obj.put("title",title);obj.put("ext",ext);}catch(JSONException ignored){}
    favorites().edit().putString(key,obj.toString()).apply();
  }
  private void showFavorites(){
    if(!guard())return;screen("★ Mes favoris");backAction=this::home;root.addView(button("‹ RETOUR",this::home));
    ScrollView scroll=new ScrollView(this);LinearLayout list=vertical();scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    Map<String,?> all=favorites().getAll();if(all.isEmpty()){list.addView(text("Aucun favori pour le moment",16));return;}
    for(Map.Entry<String,?> entry:all.entrySet())try{
      JSONObject obj=new JSONObject(String.valueOf(entry.getValue()));String type=obj.optString("type"),id=obj.optString("id"),title=obj.optString("title"),ext=obj.optString("ext","mp4");
      list.addView(button("▶  "+title,()->{currentMediaTitle=title;if("series".equals(type)){currentType="series";series(id);}else play(id,type,ext,this::showFavorites);}));
      list.addView(button("★  Supprimer : "+title,()->{favorites().edit().remove(entry.getKey()).apply();showFavorites();}));
    }catch(Exception ignored){}
  }
  private void showDownloads(){
    if(!guard())return;screen("Mes téléchargements");backAction=this::home;root.addView(button("‹ RETOUR",this::home));
    root.addView(button("↻ ACTUALISER",this::showDownloads));
    DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
    if(dm==null){root.addView(text("Gestionnaire indisponible",16));return;}
    Map<String,?> all=getSharedPreferences("downloads",MODE_PRIVATE).getAll();
    if(all.isEmpty()){root.addView(text("Aucun téléchargement lancé",16));return;}
    ScrollView scroll=new ScrollView(this);LinearLayout list=vertical();scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    for(Map.Entry<String,?> entry:all.entrySet()){
      if(!(entry.getValue() instanceof Long))continue;long downloadId=(Long)entry.getValue();
      DownloadManager.Query q=new DownloadManager.Query().setFilterById(downloadId);
      try(android.database.Cursor c=dm.query(q)){
        if(c!=null&&c.moveToFirst()){
          int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
          long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
          long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
          String state=status==DownloadManager.STATUS_SUCCESSFUL?"Terminé":status==DownloadManager.STATUS_FAILED?"Échec":status==DownloadManager.STATUS_PAUSED?"En pause":status==DownloadManager.STATUS_RUNNING?"En cours":"En attente";
          list.addView(text((entry.getKey().startsWith("id_")?entry.getKey().substring(3):entry.getKey())+" • "+state+(total>0?" • "+(done*100/total)+"%":""),15));
          if(status==DownloadManager.STATUS_SUCCESSFUL)list.addView(button("OUVRIR",()->{
            try{Uri uri=dm.getUriForDownloadedFile(downloadId);if(uri!=null){android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_VIEW);intent.setDataAndType(uri,"video/*");intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}}catch(Exception e){toast("Aucune application compatible");}
          }));
        }
      }catch(Exception ignored){}
      list.addView(button("ANNULER / SUPPRIMER",()->{dm.remove(downloadId);getSharedPreferences("downloads",MODE_PRIVATE).edit().remove(entry.getKey()).apply();showDownloads();}));
    }
    final int visibleScreen=screenToken;
    downloadRefresh=()->{if(visibleScreen==screenToken && authenticated)showDownloads();};
    downloadRefreshHandler.postDelayed(downloadRefresh,3500);
  }
  private void stopDownloadRefresh(){if(downloadRefresh!=null){downloadRefreshHandler.removeCallbacks(downloadRefresh);downloadRefresh=null;}}
  private String streamUrl(String folder,String id,String ext){
    return SERVER+"/"+folder+"/"+pathPart(username)+"/"+pathPart(password)+"/"+pathPart(id)+"."+ext;
  }
  private void startMedia(String ext){
    if(player==null)return;
    MediaItem.Builder media=new MediaItem.Builder().setUri(activeStreamUrl);
    if("m3u8".equals(ext))media.setMimeType(MimeTypes.APPLICATION_M3U8);
    else if("ts".equals(ext))media.setMimeType(MimeTypes.VIDEO_MP2T);
    player.setMediaItem(media.build());player.prepare();player.play();
  }
  private void restartPlayback(boolean alternate){
    if(player==null)return;
    if(!alternate){fallbackAttempts=0;fallbackBudget=0;}
    if(alternate&&alternatives.length>1)alternateIndex=(alternateIndex+1)%alternatives.length;
    String ext=alternatives[alternateIndex];
    playbackGeneration++;
    String folder=currentStreamType.equals("live")?"live":currentStreamType.equals("vod")?"movie":"series";
    activeStreamUrl=streamUrl(folder,currentStreamId,ext);
    if(playbackStatus!=null)playbackStatus.setText("Chargement du flux ("+ext+")…");
    player.stop();startMedia(ext);
  }
  interface Result{void done(JSONObject obj);}
  private void request(String path,Result callback){request(path,callback,()->{});}
  private void request(String path,Result callback,Runnable failure){
    if(!path.startsWith("/player_api.php"))return;
    final int token=screenToken, generation=requestGeneration;
    final LinearLayout owner=root;
    LinearLayout state=new LinearLayout(this);state.setOrientation(1);
    boolean silentHome=authenticated && backAction==null && (path.contains("action=get_vod_streams")||path.contains("action=get_live_streams"));
    if(!silentHome){TextView loading=text("Chargement…",14);state.addView(loading);owner.addView(state);}
    Runnable retry=()->{if(token==screenToken&&generation==requestGeneration){owner.removeView(state);request(path,callback,failure);}};
    try{networkExecutor.execute(()->{
      HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)new URL(SERVER+path).openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","IPTVPRO2-Android/2.7");connection.setInstanceFollowRedirects(true);
        int code=connection.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=connection.getInputStream()){
          byte[] buffer=new byte[8192];int n;
          while((n=in.read(buffer))!=-1){
            if(Thread.currentThread().isInterrupted())return;
            out.write(buffer,0,n);
            if(out.size()>32_000_000)throw new IOException("Response too large");
          }
        }
        String raw=out.toString("UTF-8").trim();JSONObject obj;
        if(raw.startsWith("[")){obj=new JSONObject();obj.put("items",new JSONArray(raw));}
        else if(raw.startsWith("{"))obj=new JSONObject(raw);
        else throw new IOException("Invalid JSON");
        runOnUiThread(()->{if(token!=screenToken||generation!=requestGeneration)return;owner.removeView(state);try{callback.done(obj);}catch(Exception callbackError){android.util.Log.e("IPTV_PRO2","Catalog callback failed",callbackError);toast("Erreur affichage : "+callbackError.getClass().getSimpleName());}});
      }catch(Exception e){
        final String message=e instanceof SocketTimeoutException?"Délai dépassé":e instanceof UnknownHostException?"Serveur introuvable":e instanceof JSONException?"Réponse du serveur invalide":e instanceof IOException?"Erreur réseau : "+(e.getMessage()==null?"connexion impossible":e.getMessage()):"Connexion impossible";
        runOnUiThread(()->{
          if(token!=screenToken||generation!=requestGeneration)return;
          if(!silentHome){state.removeAllViews();state.addView(text(message,14));}
          try{failure.run();}catch(Exception callbackError){android.util.Log.e("IPTV_PRO2","Request failure callback",callbackError);}if(!silentHome)state.addView(button("RÉESSAYER",retry));
        });
      }finally{if(connection!=null)connection.disconnect();}
    });}catch(RejectedExecutionException ex){
      state.removeAllViews();state.addView(text("Trop de requêtes. Réessayez.",14));state.addView(button("RÉESSAYER",retry));failure.run();
    }
  }
  private String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
  private String pathPart(String s){return enc(s).replace("+","%20");}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  private void setPlayerFullscreen(boolean enabled){
    if(activePlayerView==null||!playbackScreen)return;
    fullscreenPlayer=enabled;
    if(activePlayerHeading!=null)activePlayerHeading.setVisibility(enabled?View.GONE:View.VISIBLE);
    if(activePlayerBack!=null)activePlayerBack.setVisibility(View.VISIBLE);
    if(activePlayerStatus!=null)activePlayerStatus.setVisibility(enabled?View.GONE:View.VISIBLE);
    if(activePlayerControls!=null)activePlayerControls.setVisibility(enabled?View.GONE:View.VISIBLE);
    if(playerScreenRoot!=null){
      for(int i=0;i<playerScreenRoot.getChildCount();i++){
        View child=playerScreenRoot.getChildAt(i);
        if(child instanceof LinearLayout)child.setVisibility(enabled?View.GONE:View.VISIBLE);
      }
      playerScreenRoot.setPadding(enabled?0:dp(18),enabled?0:dp(14),enabled?0:dp(18),enabled?0:dp(16));
    }
    getWindow().getDecorView().setSystemUiVisibility(enabled?(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY):0);
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
  }
  private void stopHeroRotation(){if(heroRotation!=null){heroHandler.removeCallbacks(heroRotation);heroRotation=null;}}
  private void release(){if(fullscreenPlayer)setPlayerFullscreen(false);activePlayerView=null;activePlayerControls=null;activePlayerHeading=null;activePlayerBack=null;activePlayerStatus=null;playerScreenRoot=null;playbackGeneration++;if(player!=null){savePosition();player.release();player=null;}playbackScreen=false;}
  @Override public void onBackPressed(){if(fullscreenPlayer){setPlayerFullscreen(false);return;}if(backAction!=null)backAction.run();else if(authenticated)home();else super.onBackPressed();}
  @Override protected void onStop(){
    if(player!=null&&playbackScreen){lastPosition=player.getCurrentPosition();savePosition();resumeOnForeground=player.isPlaying();player.pause();}
    super.onStop();
  }
  @Override protected void onStart(){super.onStart();if(player!=null&&playbackScreen&&resumeOnForeground){if(!"live".equals(currentMediaType)&&lastPosition>0)player.seekTo(lastPosition);player.play();}}
  @Override protected void onDestroy(){stopDownloadRefresh();stopHeroRotation();filterGeneration++;filterExecutor.shutdownNow();networkExecutor.shutdownNow();artworkExecutor.shutdownNow();searchHandler.removeCallbacksAndMessages(null);release();super.onDestroy();}
}

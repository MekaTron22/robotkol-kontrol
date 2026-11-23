import sys
W,H=1660,1210
o=[]
C=dict(v12='#d32f2f',v5='#ef6c00',vs='#f9a825',gnd='#222',sig1='#1565c0',sig2='#2e7d32',sig3='#6a1b9a',i2c1='#00838f',i2c2='#ad1457',mot='#5d4037',servo='#e65100')
def a(s): o.append(s)
def line(pts,col,w=2.4):
    a('<polyline fill="none" stroke="%s" stroke-width="%s" stroke-linejoin="round" points="%s"/>'%(col,w,' '.join('%d,%d'%p for p in pts)))
def dot(x,y,col='#222'): a('<circle cx="%d" cy="%d" r="4.5" fill="%s"/>'%(x,y,col))
def text(x,y,t,size=14,anchor='start',col='#111',bold=False,italic=False):
    a('<text x="%d" y="%d" font-size="%d" text-anchor="%s" fill="%s"%s%s>%s</text>'%(x,y,size,anchor,col,' font-weight="700"' if bold else '',' font-style="italic"' if italic else '',t))
def box(x,y,w,h,title,fill='#eef3fb',stroke='#1f3b63',sub=None):
    a('<rect x="%d" y="%d" width="%d" height="%d" rx="10" fill="%s" stroke="%s" stroke-width="2.5"/>'%(x,y,w,h,fill,stroke))
    text(x+w/2,y+26,title,18,'middle',stroke,True)
    if sub: text(x+w/2,y+46,sub,12,'middle','#555')
def pinR(x,y,name,col='#111'):
    a('<circle cx="%d" cy="%d" r="4" fill="#fff" stroke="#333" stroke-width="1.5"/>'%(x,y)); text(x-10,y+5,name,14,'end',col,True)
def pinL(x,y,name,col='#111'):
    a('<circle cx="%d" cy="%d" r="4" fill="#fff" stroke="#333" stroke-width="1.5"/>'%(x,y)); text(x+10,y+5,name,14,'start',col,True)
def gnd(x,y):
    line([(x,y),(x,y+12)],C['gnd']); 
    for i,wd in enumerate((16,10,4)): line([(x-wd,y+12+i*5),(x+wd,y+12+i*5)],C['gnd'],2)
def flag(x,y,t,col,direction='right'):
    w=len(t)*8+18
    if direction=='right':
        pts=[(x,y),(x+10,y-11),(x+w,y-11),(x+w,y+11),(x+10,y+11)]; tx=x+10+(w-10)/2
    else:
        pts=[(x,y),(x-10,y-11),(x-w,y-11),(x-w,y+11),(x-10,y+11)]; tx=x-10-(w-10)/2
    a('<polygon points="%s" fill="%s" stroke="%s" stroke-width="1.5"/>'%(' '.join('%d,%d'%p for p in pts),col,col))
    text(tx,y+5,t,13,'middle','#fff',True)
def resV(x,y1,y2,label,col,left=False):  # dikey direnç
    m=(y1+y2)/2
    line([(x,y1),(x,m-18)],col); line([(x,m+18),(x,y2)],col)
    a('<rect x="%d" y="%d" width="14" height="36" fill="#fff" stroke="#333" stroke-width="2"/>'%(x-7,m-18))
    text(x-12 if left else x+12,m+5,label,12,'end' if left else 'start','#333',True)

a('<rect width="%d" height="%d" fill="#ffffff"/>'%(W,H))
text(W/2,30,'Robot Kollu Rover – Bağlantı Şeması (2× Arduino Uno + HC-05 + L293D + 7 servo)',22,'middle','#111',True)

# ---------------- Güç bölümü ----------------
box(30,55,610,170,'GÜÇ',fill='#fff7f2',stroke='#8d3b12')
a('<rect x="50" y="95" width="120" height="60" rx="6" fill="#fff" stroke="#333" stroke-width="2"/>')
text(110,120,'3S Li-ion',14,'middle','#111',True); text(110,140,'11.1–12.6 V',12,'middle','#555')
line([(170,110),(200,110)],C['v12'],3)
# anahtar
dot(200,110,C['v12']); line([(200,110),(236,96)],'#333',3); dot(240,110,C['v12']); text(220,90,'Anahtar',12,'middle','#333')
line([(240,110),(330,110)],C['v12'],3); dot(290,110,C['v12'])
line([(290,110),(290,165)],C['v12'],3); flag(290,165,'+12V',C['v12'])
a('<rect x="330" y="88" width="160" height="62" rx="6" fill="#fff" stroke="#333" stroke-width="2"/>')
text(410,112,'BUCK (servo)',14,'middle','#111',True); text(410,130,'XL4015 ≥5 A',12,'middle','#555'); text(410,145,'çıkış 5.5–6 V',12,'middle','#555')
line([(490,110),(600,110)],C['vs'],3); dot(545,110,C['vs'])
flag(600,110,'+6V SERVO',C['vs'],'left') if False else None
text(628,100,'+6V SERVO',12,'end',C['servo'],True)
line([(545,110),(545,140)],C['vs'],3)
a('<line x1="533" y1="140" x2="557" y2="140" stroke="#333" stroke-width="3"/><line x1="533" y1="148" x2="557" y2="148" stroke="#333" stroke-width="3"/>')
text(563,150,'2200 µF',12,'start','#333')
gnd(545,148); gnd(110,155); gnd(410,150)
text(50,212,'Uno VIN ← +12V   •   Servolar yalnız BUCK çıkışından   •   Tüm GND ortak',12,'start','#8d3b12',True)

# ---------------- Notlar ----------------
box(665,55,965,170,'ÖNEMLİ NOTLAR',fill='#fffde7',stroke='#8a6d00')
notes=['1. İki Uno, HC-05, L293D, buck ve servoların GND uçlarının hepsi birbirine bağlı olmalı.',
 '2. Servo beslemesi en az 5 A olmalı; LM2596 (3 A) yetersiz → Uno resetlenir, Bluetooth kopar.',
 '3. HC-05 RXD 3.3 V\'tur → Master D9 hattında 1 kΩ / 2 kΩ gerilim bölücü şart.',
 '4. L293D kanal başı 0.6 A sürekli; motorlar zorlanırsa ısınır (yazılımda yumuşak kalkış var).',
 '5. L293D Motor Shield kullanılırsa: shield Master\'ın üstüne takılır, motorlar M1 (sol) / M2 (sağ),',
 '    kodda MOTOR_DRIVER = DRIVER_L293D_SHIELD. HC-05, I2C ve LED bağlantıları aynı kalır.']
for i,t in enumerate(notes): text(685,105+i*20,t,13,'start','#333')

# ---------------- MASTER UNO ----------------
MX,MY,MW,MH=140,260,300,560
box(MX,MY,MW,MH,'MASTER UNO','#e8f0fb','#1f3b63','Bluetooth + sürüş + I2C master')
R=MX+MW
P={}
for i,(n,y) in enumerate([('VIN',300),('GND',336),('5V',372),('D10',408),('D9',444),('D5',480),('D7',516),('D8',552),('D6',588),('D4',624),('D12',660),('A4/SDA',716),('A5/SCL',752)]):
    pinR(R,y,n); P[n]=(R,y)
pinL(MX,690,'A1'); text(MX+12,800,'D13 = durum LED (kart üstü)',11,'start','#555',italic=True)
# güç
line([P['VIN'],(R+20,300)],C['v12']); flag(R+20,300,'+12V',C['v12'])
line([P['GND'],(R+30,336)],C['gnd']); gnd(R+30,336)

# ---------------- HC-05 ----------------
HX,HY,HW,HH=745,245,190,175
box(HX,HY,HW,HH,'HC-05','#e9f7ef','#1b5e20','Bluetooth SPP, 9600')
for n,y in [('VCC',310),('TXD',345),('RXD',380)]: pinL(HX,y,n)
pinB=(HX+HW/2,HY+HH); a('<circle cx="%d" cy="%d" r="4" fill="#fff" stroke="#333" stroke-width="1.5"/>'%pinB); text(pinB[0],pinB[1]-10,'GND',14,'middle','#111',True)
line([pinB,(pinB[0],pinB[1]+18)],C['gnd']); gnd(pinB[0],pinB[1]+18)
# 5V -> VCC
line([P['5V'],(R+110,372),(R+110,310),(HX,310)],C['v5']); text(R+150,303,'5V',12,'start',C['v5'],True)
# D10 <- TXD
line([P['D10'],(R+140,408),(R+140,345),(HX,345)],C['sig1'])
# D9 -> 1k -> node -> RXD ; node -> 2k -> GND
x9=R+190
line([P['D9'],(x9,444)],C['sig2']); resV(x9,444,380,'1 kΩ',C['sig2'],left=True)
line([(x9,380),(HX,380)],C['sig2']); dot(x9+40,380,C['sig2'])
resV(x9+40,380,440,'2 kΩ',C['gnd']); gnd(x9+40,440)


# ---------------- L293D ----------------
LX,LY,LW,LH=700,470,230,340
box(LX,LY+0,LW,LH,'L293D','#fdecea','#8e1b1b'); text(LX+LW/2,LY+LH-34,'(parantez: entegre pin no)',11,'middle','#555',italic=True)
ins=[('EN1 (1)','D5',C['sig3']),('IN1 (2)','D7',C['sig1']),('IN2 (7)','D8',C['sig2']),('EN2 (9)','D6',C['sig3']),('IN3 (10)','D4',C['sig1']),('IN4 (15)','D12',C['sig2'])]
for n,m,col in ins:
    y=P[m][1]; pinL(LX,y,n); line([P[m],(LX,y)],col)
for n,y,fl,col in [('VCC1 (16)',710,'+5V',C['v5']),('VCC2 (8)',750,'+12V',C['v12'])]:
    pinL(LX,y,n); line([(LX,y),(LX-40,y)],col); flag(LX-40,y,fl,col,'left')
gx=LX+LW/2; a('<circle cx="%d" cy="%d" r="4" fill="#fff" stroke="#333" stroke-width="1.5"/>'%(gx,LY+LH)); text(gx,LY+LH-10,'GND (4,5,12,13)',12,'middle','#111',True)
line([(gx,LY+LH),(gx,LY+LH+8)],C['gnd']); gnd(gx,LY+LH+8)
outs=[('OUT1 (3)',500),('OUT2 (6)',560),('OUT3 (11)',640),('OUT4 (14)',700)]
for n,y in outs: pinR(LX+LW,y,n)
# motorlar
for (t,y1,y2) in [('SOL motor',500,560),('SAĞ motor',640,700)]:
    mx=1010
    a('<circle cx="%d" cy="%d" r="38" fill="#fff" stroke="%s" stroke-width="2.5"/>'%(mx+40,(y1+y2)/2,C['mot']))
    text(mx+40,(y1+y2)/2+6,'M',22,'middle',C['mot'],True); text(mx+40,y2+40,t,13,'middle',C['mot'],True)
    line([(LX+LW,y1),(mx+12,y1),(mx+12,(y1+y2)/2-14),(mx+4,(y1+y2)/2-14)],C['mot']) if False else None
    line([(LX+LW,y1),(mx+40-27,y1)],C['mot']); line([(LX+LW,y2),(mx+40-27,y2)],C['mot'])
text(1050,780,'Motor ters dönerse: uçları değiştir',11,'middle','#555',italic=True)
text(1050,795,'veya uygulamada Ayarlar → motor ters',11,'middle','#555',italic=True)

# ---------------- LED ----------------
line([(MX,690),(MX-50,690),(MX-50,720)],C['sig3']); resV(MX-50,720,780,'220 Ω',C['sig3'])
a('<polygon points="%d,%d %d,%d %d,%d" fill="#ffeb3b" stroke="#333" stroke-width="2"/>'%(MX-64,795,MX-36,795,MX-50,818))
line([(MX-64,818),(MX-36,818)],'#333',2); line([(MX-50,780),(MX-50,795)],C['sig3']); line([(MX-50,818),(MX-50,830)],C['gnd']); gnd(MX-50,830)
text(MX-110,870,'Far LED',12,'start','#333',True); text(MX-110,885,'(4 LED → transistör)',11,'start','#555')

# ---------------- SLAVE UNO ----------------
SX,SY,SW,SH=1290,260,320,560
box(SX,SY,SW,SH,'SLAVE UNO','#f3e8fb','#4a148c','7 servo + I2C slave (0x08)')
for n,y in [('VIN',300),('GND',336),('A4/SDA',716),('A5/SCL',752)]: pinL(SX,y,n)
line([(SX,300),(SX-50,300)],C['v12']); flag(SX-50,300,'+12V',C['v12'],'left')
line([(SX,336),(SX-30,336)],C['gnd']); gnd(SX-30,336)
servos=[('D3','Root','kök'),('D5','Arm A1','omuz'),('D6','Arm A2','omuz, A1 tersi'),('D9','Arm B','dirsek'),('D10','Wrist A','bilek'),('D11','Wrist B','rotasyon'),('D12','Gripper','kıskaç')]
sp=[SX+40+i*40 for i in range(7)]
for i,(pn,_,_) in enumerate(servos):
    x=sp[i]; a('<circle cx="%d" cy="%d" r="4" fill="#fff" stroke="#333" stroke-width="1.5"/>'%(x,SY+SH))
    a('<text x="%d" y="%d" font-size="13" font-weight="700" text-anchor="start" transform="rotate(-90 %d %d)">%s</text>'%(x+5,SY+SH-10,x+5,SY+SH-10,pn))
# I2C
line([P['A4/SDA'],(R+30,716),(R+30,860),(SX-80,860),(SX-80,716),(SX,716)],C['i2c1'],2.8)
line([P['A5/SCL'],(R+15,752),(R+15,880),(SX-60,880),(SX-60,752),(SX,752)],C['i2c2'],2.8)
text(1060,853,'SDA (A4 ↔ A4)',13,'middle',C['i2c1'],True)
text((R+SX)/2,898,'SCL (A5 ↔ A5)   –  kısa tutun, isteğe bağlı 4.7 kΩ pull-up',13,'middle',C['i2c2'],True)

# ---------------- Servolar ----------------
SY0=1000
for i,(pn,name,tr) in enumerate(servos):
    bx=130+i*195; cx=bx+75
    a('<rect x="%d" y="%d" width="150" height="70" rx="8" fill="#e3e8f4" stroke="#283593" stroke-width="2"/>'%(bx,SY0))
    text(cx,SY0+28,name,15,'middle','#1a237e',True); text(cx,SY0+48,tr,12,'middle','#333')
    sxp=bx+30; laneY=915+i*11
    line([(sp[i],SY+SH),(sp[i],laneY),(sxp,laneY),(sxp,SY0)],C['servo'],2.2)
    text(sxp+4,SY0-6,pn,11,'start',C['servo'],True)
    # güç
    line([(cx,SY0+70),(cx,1105)],C['vs'],2.2); dot(cx,1105,C['vs'])
    line([(bx+120,SY0+70),(bx+120,1135)],C['gnd'],2.2); dot(bx+120,1135)
line([(205,1105),(1500,1105)],C['vs'],4); flag(1500,1105,'+6V SERVO',C['servo'])
line([(250,1135),(1460,1135)],C['gnd'],4); gnd(1460,1135)
text(130,1170,'Her servo: turuncu/sarı = sinyal (Slave pini), kırmızı = +6V SERVO, kahverengi/siyah = GND.  Servolar sırayla (250 ms arayla) bağlanır.',13,'start','#333')
text(130,1190,'Arm A1 ve Arm A2 aynı omuz eklemini birlikte döndürür: A2 açısı yazılımda 180 − A1 (+ SHOULDER_A2_TRIM).',13,'start','#333')

svg='<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="DejaVu Sans, Arial, sans-serif">%s</svg>'%(W,H,W,H,''.join(o))
open(sys.argv[1],'w').write(svg)

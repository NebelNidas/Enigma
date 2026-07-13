# Human spot-check of the semantic-judge panel (2026-07-11)

**Purpose:** independent human validation of the blinded LLM judge panel that produced the
semantic-recovery numbers. For each residual below: the obfuscated member, its decompiled behaviour,
the model's SUGGESTED name, and the true REFERENCE name. The panel's verdict (majority, reference
withheld) is hidden at the bottom of each item so you can decide first, then compare.

Sample: 26 residuals, ~balanced accepted/rejected, across the 8b/14b/30B local models, seed 20260711.
Mark each: **agree** / **disagree** with the panel, so we can report human-vs-panel concordance.

---

### 1. `14b` — METHOD in `C114`  (obf `m1330`)
- **descriptor:** `(Ljava/lang/StringBuffer;Ljava/lang/String;[D)V`
- **suggested:** `appendDoubles`   (alts: addDoubleArray, processDoubleValues)
- **reference (true):** `appendDetail`
- **decompiled context:** `protected void m1330(StringBuffer var1, String var2, double[] var3) {       var1.append(this.f377);        for (int var4 = 0; var4 < var3.length; var4++) {          if (var4 > 0) {             var1.append(this.f376);          }           this.m1471(var1, var2, var3[var4]);       }        var1.append(this.f375);    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 2. `8b` — METHOD in `C83`  (obf `m174`)
- **descriptor:** `()Z`
- **suggested:** `isValid`   (alts: checkFlag, validateState)
- **reference (true):** `isStarted`
- **decompiled context:** `public boolean m174() {       return this.f213 != -1;    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 3. `8b` — METHOD in `C137`  (obf `m300`)
- **descriptor:** `(F)Lobf/C223;`
- **suggested:** `validateFloat`   (alts: checkFloatValue)
- **reference (true):** `value`
- **decompiled context:** `public C223 m300(float var1) throws IOException {       if (this.m385() || !Float.isNaN(var1) && !Float.isInfinite(var1)) {          this.m298(new C31(var1));          return this;       } else {          throw new IllegalArgumentException("JSON forbids NaN and infinities: " + var1);       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 4. `14b` — METHOD in `C315`  (obf `m2345`)
- **descriptor:** `()Z`
- **suggested:** `isConditionMet`   (alts: isValidState, checkStatus)
- **reference (true):** `isEmptyTokenAsNull`
- **decompiled context:** `if (this.m2345()) {             var2 = null;          }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 5. `8b` — FIELD in `C161`  (obf `f208`)
- **descriptor:** `Lobf/C54;`
- **suggested:** `FACTORY_DESCRIPTOR`   (alts: FACTORY_CONFIG, TYPE_DEFINITION)
- **reference (true):** `INTEGER_FACTORY`
- **decompiled context:** `// field declaration: public static final C54 f208 = newFactory(int.class, Integer.class, f207); // sibling fields in the same class: public static final C52<Class> f198 = (new C52<Class>() { public static final C54 f199 = m332(Class.class, f198); public static final C52<BitSet> f187 = (new C52<BitSet>() { for (C222 var4 = var1.m288(); var4 != C222.f299; var4 = var1.m288()) { public static final C54 f188 = m332(BitSet.class, f187); public static final C52<Boolean> f189 = new C52<Boolean>() { public static final C52<Boolean> f190 = new C52<Boolean>() { public static final C54 f191 = newFactory( …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 6. `14b` — FIELD in `C83`  (obf `f71`)
- **descriptor:** `I`
- **suggested:** `DEFAULT_LIMIT`   (alts: MAX_VALUE, CONFIGURED_SIZE)
- **reference (true):** `MF_BT4`
- **decompiled context:** `// field declaration: public static final int f71 = 20; // sibling fields in the same class: public static final int f72 = 4; public static final int f71 = 20; private final int f210; private final int f209; final int f211; final int f88; final byte[] f201; final int f202; int f213 = -1; private int f212 = -1; private boolean f208 = false; private int f214 = 0; private int f97 = 0; this.f202 = m167(var1, var2, var3, var5); this.f201 = var6.m4(this.f202, false); this.f210 = var2 + var1; this.f209 = var3 + var5; this.f211 = var5; this.f88 = var4; assert this.f214 == 0; assert !this.f208; var3 =  …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 7. `8b` — METHOD in `C13`  (obf `m51`)
- **descriptor:** `(Ljava/lang/Object;Ljava/lang/Appendable;)V`
- **suggested:** `write`   (alts: format, serialize)
- **reference (true):** `toJson`
- **decompiled context:** `public void m51(Object var1, Appendable var2) throws C26 {       if (var1 != null) {          this.m54(var1, var1.getClass(), var2);       } else {          this.m49(C27.f59, var2);       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 8. `30b` — CLASS in `C25`  (obf `C25`)
- **descriptor:** ``
- **suggested:** `XzFilter`   (alts: FilterChain, XzValidator)
- **reference (true):** `FilterCoder`
- **decompiled context:** `interface C25 {    boolean m11();     boolean m14();     boolean m13(); }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 9. `8b` — METHOD in `C74`  (obf `m92`)
- **descriptor:** `()J`
- **suggested:** `getOffset`   (alts: calculateOffset, computePosition)
- **reference (true):** `getLargestBlockSize`
- **decompiled context:** `public long m92() {       return this.f121;    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 10. `14b` — METHOD in `C137`  (obf `m270`)
- **descriptor:** `()Lobf/C223;`
- **suggested:** `createJsonObject`   (alts: buildJsonObject, generateJsonObject)
- **reference (true):** `beginObject`
- **decompiled context:** `public C223 m270() throws IOException {       C28 var1 = new C28();       this.m298(var1);       this.f151.add(var1);       return this;    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 11. `8b` — FIELD in `C161`  (obf `f227`)
- **descriptor:** `Lobf/C52;`
- **suggested:** `TYPE_DESCRIPTOR`   (alts: TYPE_CONSTANT, CLASS_TOKEN)
- **reference (true):** `UUID`
- **decompiled context:** `// field declaration: public static final C52<UUID> f227 = new C52<UUID>() { // sibling fields in the same class: public static final C52<Class> f198 = (new C52<Class>() { public static final C54 f199 = m332(Class.class, f198); public static final C52<BitSet> f187 = (new C52<BitSet>() { for (C222 var4 = var1.m288(); var4 != C222.f299; var4 = var1.m288()) { public static final C54 f188 = m332(BitSet.class, f187); public static final C52<Boolean> f189 = new C52<Boolean>() { public static final C52<Boolean> f190 = new C52<Boolean>() { public static final C54 f191 = newFactory(boolean.class, Boole …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 12. `14b` — METHOD in `C107`  (obf `m244`)
- **descriptor:** `([SII)V`
- **suggested:** `processShortArray`   (alts: updateIndices, handleBitData)
- **reference (true):** `encodeBit`
- **decompiled context:** `public void m244(short[] var1, int var2, int var3) throws IOException {       short var4 = var1[var2];       int var5 = (this.f314 >>> 11) * var4;       if (var3 == 0) {          this.f314 = var5;          var1[var2] = (short)(var4 + (2048 - var4 >>> 5));       } else {          this.f320 += var5 & 4294967295L;          this.f314 -= var5;          var1[var2] = (short)(var4 - (var4 >>> 5));       }        if ((this.f314 & 0xFF000000) == 0) {          this.f314 <<= 8;          this.m253();       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 13. `8b` — METHOD in `C6`  (obf `m134`)
- **descriptor:** `(I[F[F)[F`
- **suggested:** `copyFloatArrays`   (alts: copyFloatArray, arrayCopy)
- **reference (true):** `insert`
- **decompiled context:** `public static float[] m134(int var0, float[] var1, float... var2) {       if (var1 == null) {          return null;       }        if (m145(var2)) {          return clone(var1);       }        if (var0 >= 0 && var0 <= var1.length) {          float[] var3 = new float[var1.length + var2.length];          System.arraycopy(var2, 0, var3, var0, var2.length);          if (var0 > 0) {             System.arraycopy(var1, 0, var3, 0, var0);          }           if (var0 < var1.length) {             System.arraycopy(var1, var0, var3, var0 + var2.length, var1.length - var0);          }           return va …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 14. `8b` — CLASS in `C63`  (obf `C63`)
- **descriptor:** ``
- **suggested:** `ValidationHelper`   (alts: ValidatorUtils, AssertionHelper)
- **reference (true):** `Preconditions`
- **decompiled context:** ``

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 15. `30b` — METHOD in `C37`  (obf `m50`)
- **descriptor:** `()I`
- **suggested:** `getNiceLength`   (alts: getMatchNiceLength, getNiceLengthLimit)
- **reference (true):** `getNiceLen`
- **decompiled context:** `public int m50() {       return this.f88;    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 16. `30b` — CLASS in `C3`  (obf `C3`)
- **descriptor:** ``
- **suggested:** `Config`   (alts: Settings, DataHandler)
- **reference (true):** `ArrayCache`
- **decompiled context:** `public class C3 {    private static final C3 f3 = new C3();    private static volatile C3 f2 = f3;     public static C3 m6() {       return f3;    }     public static C3 m5() {       return f2;    }     public static void m10(C3 var0) {       if (var0 == null) {          throw new NullPointerException();       }        f2 = var0;    }     public byte[] m4(int var1, boolean var2) {       return new byte[var1];    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 17. `8b` — CLASS in `C62`  (obf `C62`)
- **descriptor:** ``
- **suggested:** `SystemProperties`   (alts: SystemConfig, RuntimeSettings, EnvironmentConstants)
- **reference (true):** `SystemUtils`
- **decompiled context:** `public class C62 {    private static final String f290 = "Windows";    public static final String f173 = C61.m1068();    @Deprecated    public static final String f174 = C61.m1069();    public static final String f175 = C61.m1071();    public static final String f176 = C61.m1072();    public static final String f177 = C61.m1073();    public static final String f178 = C61.m1074();    public static final String f179 = C61.m1075();    public static final String f180 = C61.m1076();    public static final String f181 = C61.m1077();    public static final String f182 = C61.m1078();    public static  …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 18. `8b` — CLASS in `C283`  (obf `C283`)
- **descriptor:** ``
- **suggested:** `TypeBounds`   (alts: WildcardHandler)
- **reference (true):** `WildcardTypeBuilder`
- **decompiled context:** `public class C279 {    public static final WildcardType f555 = m2100().m2105(Object.class).m1218();     private static <T> StringBuilder m2055(StringBuilder var0, String var1, T... var2) {       C70.m1192(C70.m1182(var2));       if (var2.length > 0) {          var0.append(toString(var2[0]));           for (int var3 = 1; var3 < var2.length; var3++) {             var0.append(var1).append(toString(var2[var3]));          }       }        return var0;    }     private static void m2056(StringBuilder var0, int[] var1, Type[] var2) {       for (int var3 = 0; var3 < var1.length; var3++) {          m20 …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 19. `30b` — METHOD in `C23`  (obf `m123`)
- **descriptor:** `(Lobf/C25;Ljava/lang/reflect/Type;)Ljava/lang/Object;`
- **suggested:** `evaluate`   (alts: apply, process, compute)
- **reference (true):** `deserialize`
- **decompiled context:** `(body not found)`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 20. `30b` — METHOD in `C62`  (obf `m1110`)
- **descriptor:** `()Ljava/io/File;`
- **suggested:** `getUserDirectoryFile`   (alts: getHomeDirectoryFile, getUserConfigFile)
- **reference (true):** `getUserHome`
- **decompiled context:** `public static File m1110() {       return new File(C61.m1110());    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 21. `30b` — METHOD in `C137`  (obf `m300`)
- **descriptor:** `(F)Lobf/C223;`
- **suggested:** `floatValue`   (alts: writeFloat, addFloat, value)
- **reference (true):** `value`
- **decompiled context:** `public C223 m300(float var1) throws IOException {       if (this.m385() || !Float.isNaN(var1) && !Float.isInfinite(var1)) {          this.m298(new C31(var1));          return this;       } else {          throw new IllegalArgumentException("JSON forbids NaN and infinities: " + var1);       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 22. `8b` — FIELD in `C398`  (obf `f335`)
- **descriptor:** `Ljava/lang/Object;`
- **suggested:** `value`   (alts: data, content)
- **reference (true):** `right`
- **decompiled context:** `// field declaration: public R f335; // sibling fields in the same class: public static final C398<?, ?, ?>[] f792 = new C398[0]; private static final long f46 = 1L; public M f790; public R f335; return (C398<L, M, R>[])f792; this.f790 = (M)var2; this.f335 = (R)var3; return this.f790; return this.f335; this.f790 = (M)var1; this.f335 = (R)var1;`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **REJECT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 23. `30b` — METHOD in `C137`  (obf `m296`)
- **descriptor:** `(Ljava/lang/String;)Lobf/C223;`
- **suggested:** `writeName`   (alts: setName, nameValue, addName)
- **reference (true):** `name`
- **decompiled context:** `public C223 m296(String var1) throws IOException {       Objects.requireNonNull(var1, "name == null");       if (!this.f151.isEmpty() && this.f149 == null) {          C25 var2 = this.m288();          if (var2 instanceof C28) {             this.f149 = var1;             return this;          } else {             throw new IllegalStateException("Please begin an object before writing a name.");          }       } else {          throw new IllegalStateException("Did not expect a name");       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 24. `14b` — METHOD in `C26`  (obf `m630`)
- **descriptor:** `(Ljava/lang/Class;[J)Ljava/util/EnumSet;`
- **suggested:** `createEnumSetFromBitVectors`   (alts: generateEnumSetFromBits, convertBitArrayToEnumSet)
- **reference (true):** `processBitVectors`
- **decompiled context:** `public static <E extends Enum<E>> EnumSet<E> m630(Class<E> var0, long... var1) {       EnumSet var2 = EnumSet.noneOf(m612(var0));       long[] var3 = C6.clone(Objects.requireNonNull(var1, "values"));       C6.m291(var3);        for (Enum var7 : (Enum[])var0.getEnumConstants()) {          int var8 = var7.ordinal() / 64;          if (var8 < var3.length && (var3[var8] & 1L << var7.ordinal() % 64) != 0L) {             var2.add((E)var7);          }       }        return var2;    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 25. `30b` — CLASS in `C186`  (obf `C186`)
- **descriptor:** ``
- **suggested:** `ListenerManager`   (alts: ProxyListenerRegistry, DynamicListenerHandler)
- **reference (true):** `EventListenerSupport`
- **decompiled context:** `public class C186<L> implements Serializable {    private static final long f46 = 3593265990380473632L;    private List<L> f454 = new CopyOnWriteArrayList<>();    private transient L f456;    private transient L[] f455;     public static <T> C186<T> m1636(Class<T> var0) {       return new C186(var0);    }     private C186() {    }     public C186(Class<L> var1) {       this(var1, Thread.currentThread().getContextClassLoader());    }     public C186(Class<L> var1, ClassLoader var2) {       this();       Objects.requireNonNull(var1, "listenerInterface");       Objects.requireNonNull(var2, "class …`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

### 26. `30b` — CLASS in `C5`  (obf `C5`)
- **descriptor:** ``
- **suggested:** `BcjFilter`   (alts: BcjProperties, CompressionFilter, FilterHandler)
- **reference (true):** `BCJDecoder`
- **decompiled context:** `class C5 extends C4 implements C26 {    private final long f10;    private final int f11;     C5(long var1, byte[] var3) throws C53 {       assert m12(var1);       this.f10 = var1;       if (var3.length == 0) {          this.f11 = 0;       } else {          if (var3.length != 4) {             throw new C53("Unsupported BCJ filter properties");          }           int var4 = 0;           for (int var5 = 0; var5 < 4; var5++) {             var4 |= (var3[var5] & 255) << var5 * 8;          }           this.f11 = var4;       }    }`

  <details><summary>panel verdict</summary>

  reference-withheld majority: **ACCEPT**  ·  your call: ____ (agree / disagree)
  </details>

---

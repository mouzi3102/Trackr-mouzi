package com.trackr.core.model

/**
 * [ProvinceCityCatalog] 城市目录的手写拼音索引：每个收录城市一条「音节分隔的无声调全拼」（如 玉林 → "yu-lin"）。
 *
 * 全拼 = 去掉分隔符（yulin），首字母 = 每个音节取首字母（yl；zh/ch/sh 记作 z/c/s）。
 * 只放 commonMain 纯字符串表，不引任何新依赖 / JVM 库。多音字取该地名的通行读音
 * （重庆 chong-qing、乐清 yue-qing、长春 chang-chun、六安 lu-an、蚌埠 beng-bu、什邡 shi-fang…）。
 * 覆盖表以 [ProvinceCityCatalog.ALL_CITY_NAMES] 为准，测试逐条断言无缺漏。
 */
object CityPinyinIndex {

    private val SEGMENTS: Map<String, String> = mapOf(
        // 直辖市
        "北京" to "bei-jing", "上海" to "shang-hai", "天津" to "tian-jin", "重庆" to "chong-qing",
        // 广东
        "广州" to "guang-zhou", "深圳" to "shen-zhen", "珠海" to "zhu-hai", "汕头" to "shan-tou",
        "佛山" to "fo-shan", "韶关" to "shao-guan", "湛江" to "zhan-jiang", "肇庆" to "zhao-qing",
        "江门" to "jiang-men", "茂名" to "mao-ming", "惠州" to "hui-zhou", "梅州" to "mei-zhou",
        "汕尾" to "shan-wei", "河源" to "he-yuan", "阳江" to "yang-jiang", "清远" to "qing-yuan",
        "东莞" to "dong-guan", "中山" to "zhong-shan", "潮州" to "chao-zhou", "揭阳" to "jie-yang",
        "云浮" to "yun-fu", "顺德" to "shun-de", "南海" to "nan-hai", "开平" to "kai-ping",
        "台山" to "tai-shan", "普宁" to "pu-ning", "罗定" to "luo-ding", "信宜" to "xin-yi",
        "高州" to "gao-zhou", "化州" to "hua-zhou", "廉江" to "lian-jiang", "吴川" to "wu-chuan",
        "雷州" to "lei-zhou", "四会" to "si-hui", "恩平" to "en-ping", "鹤山" to "he-shan",
        "英德" to "ying-de", "连州" to "lian-zhou", "乐昌" to "le-chang", "南雄" to "nan-xiong",
        "兴宁" to "xing-ning", "陆丰" to "lu-feng",
        // 江苏
        "南京" to "nan-jing", "无锡" to "wu-xi", "徐州" to "xu-zhou", "常州" to "chang-zhou",
        "苏州" to "su-zhou", "南通" to "nan-tong", "连云港" to "lian-yun-gang", "淮安" to "huai-an",
        "盐城" to "yan-cheng", "扬州" to "yang-zhou", "镇江" to "zhen-jiang", "泰州" to "tai-zhou",
        "宿迁" to "su-qian", "江阴" to "jiang-yin", "宜兴" to "yi-xing", "邳州" to "pi-zhou",
        "新沂" to "xin-yi", "溧阳" to "li-yang", "常熟" to "chang-shu", "张家港" to "zhang-jia-gang",
        "昆山" to "kun-shan", "太仓" to "tai-cang", "如皋" to "ru-gao", "海门" to "hai-men",
        "启东" to "qi-dong", "东台" to "dong-tai", "高邮" to "gao-you", "仪征" to "yi-zheng",
        "丹阳" to "dan-yang", "扬中" to "yang-zhong", "句容" to "ju-rong", "靖江" to "jing-jiang",
        "泰兴" to "tai-xing", "兴化" to "xing-hua",
        // 浙江
        "杭州" to "hang-zhou", "宁波" to "ning-bo", "温州" to "wen-zhou", "嘉兴" to "jia-xing",
        "湖州" to "hu-zhou", "绍兴" to "shao-xing", "金华" to "jin-hua", "衢州" to "qu-zhou",
        "舟山" to "zhou-shan", "台州" to "tai-zhou", "丽水" to "li-shui", "余姚" to "yu-yao",
        "慈溪" to "ci-xi", "乐清" to "yue-qing", "瑞安" to "rui-an", "海宁" to "hai-ning",
        "平湖" to "ping-hu", "桐乡" to "tong-xiang", "诸暨" to "zhu-ji", "嵊州" to "sheng-zhou",
        "兰溪" to "lan-xi", "义乌" to "yi-wu", "东阳" to "dong-yang", "永康" to "yong-kang",
        "江山" to "jiang-shan", "温岭" to "wen-ling", "临海" to "lin-hai", "玉环" to "yu-huan",
        "龙港" to "long-gang",
        // 山东
        "济南" to "ji-nan", "青岛" to "qing-dao", "淄博" to "zi-bo", "枣庄" to "zao-zhuang",
        "东营" to "dong-ying", "烟台" to "yan-tai", "潍坊" to "wei-fang", "济宁" to "ji-ning",
        "泰安" to "tai-an", "威海" to "wei-hai", "日照" to "ri-zhao", "临沂" to "lin-yi",
        "德州" to "de-zhou", "聊城" to "liao-cheng", "滨州" to "bin-zhou", "菏泽" to "he-ze",
        "胶州" to "jiao-zhou", "平度" to "ping-du", "莱西" to "lai-xi", "滕州" to "teng-zhou",
        "龙口" to "long-kou", "莱阳" to "lai-yang", "莱州" to "lai-zhou", "招远" to "zhao-yuan",
        "栖霞" to "qi-xia", "海阳" to "hai-yang", "青州" to "qing-zhou", "诸城" to "zhu-cheng",
        "寿光" to "shou-guang", "安丘" to "an-qiu", "高密" to "gao-mi", "昌邑" to "chang-yi",
        "曲阜" to "qu-fu", "邹城" to "zou-cheng", "新泰" to "xin-tai", "肥城" to "fei-cheng",
        "乳山" to "ru-shan", "荣成" to "rong-cheng", "乐陵" to "le-ling", "禹城" to "yu-cheng",
        "临清" to "lin-qing",
        // 四川
        "成都" to "cheng-du", "自贡" to "zi-gong", "攀枝花" to "pan-zhi-hua", "泸州" to "lu-zhou",
        "德阳" to "de-yang", "绵阳" to "mian-yang", "广元" to "guang-yuan", "遂宁" to "sui-ning",
        "内江" to "nei-jiang", "乐山" to "le-shan", "南充" to "nan-chong", "眉山" to "mei-shan",
        "宜宾" to "yi-bin", "广安" to "guang-an", "达州" to "da-zhou", "雅安" to "ya-an",
        "巴中" to "ba-zhong", "资阳" to "zi-yang", "阿坝" to "a-ba", "甘孜" to "gan-zi",
        "凉山" to "liang-shan", "都江堰" to "du-jiang-yan", "彭州" to "peng-zhou", "邛崃" to "qiong-lai",
        "崇州" to "chong-zhou", "简阳" to "jian-yang", "广汉" to "guang-han", "什邡" to "shi-fang",
        "绵竹" to "mian-zhu", "江油" to "jiang-you", "峨眉山" to "e-mei-shan", "阆中" to "lang-zhong",
        "华蓥" to "hua-ying", "万源" to "wan-yuan", "西昌" to "xi-chang", "康定" to "kang-ding",
        "马尔康" to "ma-er-kang",
        // 湖北
        "武汉" to "wu-han", "黄石" to "huang-shi", "十堰" to "shi-yan", "宜昌" to "yi-chang",
        "襄阳" to "xiang-yang", "鄂州" to "e-zhou", "荆门" to "jing-men", "孝感" to "xiao-gan",
        "荆州" to "jing-zhou", "黄冈" to "huang-gang", "咸宁" to "xian-ning", "随州" to "sui-zhou",
        "恩施" to "en-shi", "仙桃" to "xian-tao", "潜江" to "qian-jiang", "天门" to "tian-men",
        "神农架" to "shen-nong-jia", "大冶" to "da-ye", "丹江口" to "dan-jiang-kou", "宜都" to "yi-du",
        "当阳" to "dang-yang", "枝江" to "zhi-jiang", "老河口" to "lao-he-kou", "枣阳" to "zao-yang",
        "宜城" to "yi-cheng", "钟祥" to "zhong-xiang", "应城" to "ying-cheng", "安陆" to "an-lu",
        "汉川" to "han-chuan", "石首" to "shi-shou", "洪湖" to "hong-hu", "松滋" to "song-zi",
        "麻城" to "ma-cheng", "武穴" to "wu-xue", "赤壁" to "chi-bi", "广水" to "guang-shui",
        "利川" to "li-chuan",
        // 湖南
        "长沙" to "chang-sha", "株洲" to "zhu-zhou", "湘潭" to "xiang-tan", "衡阳" to "heng-yang",
        "邵阳" to "shao-yang", "岳阳" to "yue-yang", "常德" to "chang-de", "张家界" to "zhang-jia-jie",
        "益阳" to "yi-yang", "郴州" to "chen-zhou", "永州" to "yong-zhou", "怀化" to "huai-hua",
        "娄底" to "lou-di", "湘西" to "xiang-xi", "浏阳" to "liu-yang", "宁乡" to "ning-xiang",
        "醴陵" to "li-ling", "湘乡" to "xiang-xiang", "韶山" to "shao-shan", "耒阳" to "lei-yang",
        "常宁" to "chang-ning", "武冈" to "wu-gang", "临湘" to "lin-xiang", "汨罗" to "mi-luo",
        "津市" to "jin-shi", "沅江" to "yuan-jiang", "资兴" to "zi-xing", "洪江" to "hong-jiang",
        "冷水江" to "leng-shui-jiang", "涟源" to "lian-yuan", "吉首" to "ji-shou",
        // 福建
        "福州" to "fu-zhou", "厦门" to "xia-men", "莆田" to "pu-tian", "三明" to "san-ming",
        "泉州" to "quan-zhou", "漳州" to "zhang-zhou", "南平" to "nan-ping", "龙岩" to "long-yan",
        "宁德" to "ning-de", "福清" to "fu-qing", "闽侯" to "min-hou", "晋江" to "jin-jiang",
        "石狮" to "shi-li", "南安" to "nan-an", "永安" to "yong-an", "龙海" to "long-hai",
        "邵武" to "shao-wu", "武夷山" to "wu-yi-shan", "建瓯" to "jian-ou", "漳平" to "zhang-ping",
        "福安" to "fu-an", "福鼎" to "fu-ding",
        // 河南
        "郑州" to "zheng-zhou", "开封" to "kai-feng", "洛阳" to "luo-yang", "平顶山" to "ping-ding-shan",
        "安阳" to "an-yang", "鹤壁" to "he-bi", "新乡" to "xin-xiang", "焦作" to "jiao-zuo",
        "濮阳" to "pu-yang", "许昌" to "xu-chang", "漯河" to "luo-he", "三门峡" to "san-men-xia",
        "南阳" to "nan-yang", "商丘" to "shang-qiu", "信阳" to "xin-yang", "周口" to "zhou-kou",
        "驻马店" to "zhu-ma-dian", "济源" to "ji-yuan", "巩义" to "gong-yi", "荥阳" to "xing-yang",
        "新密" to "xin-mi", "新郑" to "xin-zheng", "登封" to "deng-feng", "偃师" to "yan-shi",
        "舞钢" to "wu-gang", "汝州" to "ru-zhou", "林州" to "lin-zhou", "卫辉" to "wei-hui",
        "辉县" to "hui-xian", "沁阳" to "qin-yang", "孟州" to "meng-zhou", "禹州" to "yu-zhou",
        "长葛" to "chang-ge", "义马" to "yi-ma", "灵宝" to "ling-bao", "邓州" to "deng-zhou",
        "永城" to "yong-cheng", "项城" to "xiang-cheng",
        // 河北
        "石家庄" to "shi-jia-zhuang", "唐山" to "tang-shan", "秦皇岛" to "qin-huang-dao", "邯郸" to "han-dan",
        "邢台" to "xing-tai", "保定" to "bao-ding", "张家口" to "zhang-jia-kou", "承德" to "cheng-de",
        "沧州" to "cang-zhou", "廊坊" to "lang-fang", "衡水" to "heng-shui", "辛集" to "xin-ji",
        "晋州" to "jin-zhou", "新乐" to "xin-le", "遵化" to "zun-hua", "迁安" to "qian-an",
        "武安" to "wu-an", "南宫" to "nan-gong", "沙河" to "sha-he", "涿州" to "zhuo-zhou",
        "定州" to "ding-zhou", "安国" to "an-guo", "高碑店" to "gao-bei-dian", "平泉" to "ping-quan",
        "泊头" to "bo-tou", "任丘" to "ren-qiu", "黄骅" to "huang-hua", "河间" to "he-jian",
        "霸州" to "ba-zhou", "三河" to "san-he", "深州" to "shen-zhou", "雄安" to "xiong-an",
        // 广西
        "南宁" to "nan-ning", "柳州" to "liu-zhou", "桂林" to "gui-lin", "梧州" to "wu-zhou",
        "北海" to "bei-hai", "防城港" to "fang-cheng-gang", "钦州" to "qin-zhou", "贵港" to "gui-gang",
        "玉林" to "yu-lin", "百色" to "bai-se", "贺州" to "he-zhou", "河池" to "he-chi",
        "来宾" to "lai-bin", "崇左" to "chong-zuo", "岑溪" to "cen-xi", "东兴" to "dong-xing",
        "桂平" to "gui-ping", "北流" to "bei-liu", "靖西" to "jing-xi", "凭祥" to "ping-xiang",
        "合山" to "he-shan",
        // 安徽
        "合肥" to "he-fei", "芜湖" to "wu-hu", "蚌埠" to "beng-bu", "淮南" to "huai-nan",
        "马鞍山" to "ma-an-shan", "淮北" to "huai-bei", "铜陵" to "tong-ling", "安庆" to "an-qing",
        "黄山" to "huang-shan", "滁州" to "chu-zhou", "阜阳" to "fu-yang", "宿州" to "su-zhou",
        "六安" to "lu-an", "亳州" to "bo-zhou", "池州" to "chi-zhou", "宣城" to "xuan-cheng",
        "巢湖" to "chao-hu", "桐城" to "tong-cheng", "天长" to "tian-chang", "明光" to "ming-guang",
        "界首" to "jie-shou", "潜山" to "qian-shan", "广德" to "guang-de", "无为" to "wu-wei",
        // 辽宁
        "沈阳" to "shen-yang", "大连" to "da-lian", "鞍山" to "an-shan", "抚顺" to "fu-shun",
        "本溪" to "ben-xi", "丹东" to "dan-dong", "锦州" to "jin-zhou", "营口" to "ying-kou",
        "阜新" to "fu-xin", "辽阳" to "liao-yang", "盘锦" to "pan-jin", "铁岭" to "tie-ling",
        "朝阳" to "zhao-yang", "葫芦岛" to "hu-lu-dao", "新民" to "xin-min", "瓦房店" to "wa-fang-dian",
        "庄河" to "zhuang-he", "海城" to "hai-cheng", "东港" to "dong-gang", "凤城" to "feng-cheng",
        "凌海" to "ling-hai", "北镇" to "bei-zhen", "盖州" to "gai-zhou", "大石桥" to "da-shi-qiao",
        "灯塔" to "deng-ta", "调兵山" to "tiao-bing-shan", "开原" to "kai-yuan", "北票" to "bei-piao",
        "凌源" to "ling-yuan", "兴城" to "xing-cheng",
        // 陕西
        "西安" to "xi-an", "铜川" to "tong-chuan", "宝鸡" to "bao-ji", "咸阳" to "xian-yang",
        "渭南" to "wei-nan", "延安" to "yan-an", "汉中" to "han-zhong", "榆林" to "yu-lin",
        "安康" to "an-kang", "商洛" to "shang-luo", "韩城" to "han-cheng", "华阴" to "hua-yin",
        "兴平" to "xing-ping", "彬州" to "bin-zhou", "神木" to "shen-mu", "子长" to "zi-chang",
        // 江西
        "南昌" to "nan-chang", "景德镇" to "jing-de-zhen", "萍乡" to "ping-xiang", "九江" to "jiu-jiang",
        "新余" to "xin-yu", "鹰潭" to "ying-tan", "赣州" to "gan-zhou", "吉安" to "ji-an",
        "宜春" to "yi-chun", "抚州" to "fu-zhou", "上饶" to "shang-rao", "乐平" to "le-ping",
        "瑞昌" to "rui-chang", "共青城" to "gong-qing-cheng", "庐山" to "lu-shan", "贵溪" to "gui-xi",
        "瑞金" to "rui-jin", "龙南" to "long-nan", "井冈山" to "jing-gang-shan", "丰城" to "feng-cheng",
        "樟树" to "zhang-shu", "高安" to "gao-an", "德兴" to "de-xing",
        // 云南
        "昆明" to "kun-ming", "曲靖" to "qu-jing", "玉溪" to "yu-xi", "保山" to "bao-shan",
        "昭通" to "zhao-tong", "丽江" to "li-jiang", "普洱" to "pu-er", "临沧" to "lin-cang",
        "楚雄" to "chu-xiong", "红河" to "hong-he", "文山" to "wen-shan", "西双版纳" to "xi-shuang-ban-na",
        "大理" to "da-li", "德宏" to "de-hong", "怒江" to "nu-jiang", "迪庆" to "di-qing",
        "安宁" to "an-ning", "宣威" to "xuan-wei", "腾冲" to "teng-chong", "蒙自" to "meng-zi",
        "个旧" to "ge-jiu", "开远" to "kai-yuan", "弥勒" to "mi-le", "景洪" to "jing-hong",
        "瑞丽" to "rui-li", "芒市" to "mang-shi", "香格里拉" to "xiang-ge-li-la",
        // 山西
        "太原" to "tai-yuan", "大同" to "da-tong", "阳泉" to "yang-quan", "长治" to "chang-zhi",
        "晋城" to "jin-cheng", "朔州" to "shuo-zhou", "晋中" to "jin-zhong", "运城" to "yun-cheng",
        "忻州" to "xin-zhou", "临汾" to "lin-fen", "吕梁" to "lv-liang", "古交" to "gu-jiao",
        "高平" to "gao-ping", "介休" to "jie-xiu", "永济" to "yong-ji", "河津" to "he-jin",
        "原平" to "yuan-ping", "侯马" to "hou-ma", "霍州" to "huo-zhou", "孝义" to "xiao-yi",
        "汾阳" to "fen-yang",
        // 黑龙江
        "哈尔滨" to "ha-er-bin", "齐齐哈尔" to "qi-qi-ha-er", "鸡西" to "ji-xi", "鹤岗" to "he-gang",
        "双鸭山" to "shuang-ya-shan", "大庆" to "da-qing", "伊春" to "yi-chun", "佳木斯" to "jia-mu-si",
        "七台河" to "qi-tai-he", "牡丹江" to "mu-dan-jiang", "黑河" to "hei-he", "绥化" to "sui-hua",
        "大兴安岭" to "da-xing-an-ling", "尚志" to "shang-zhi", "五常" to "wu-chang", "讷河" to "ne-he",
        "虎林" to "hu-lin", "密山" to "mi-shan", "铁力" to "tie-li", "同江" to "tong-jiang",
        "富锦" to "fu-jin", "绥芬河" to "sui-fen-he", "海林" to "hai-lin", "宁安" to "ning-an",
        "穆棱" to "mu-ling", "东宁" to "dong-ning", "北安" to "bei-an", "五大连池" to "wu-da-lian-chi",
        "嫩江" to "nen-jiang", "安达" to "an-da", "肇东" to "zhao-dong", "海伦" to "hai-lun",
        "漠河" to "mo-he",
        // 吉林
        "长春" to "chang-chun", "吉林" to "ji-lin", "四平" to "si-ping", "辽源" to "liao-yuan",
        "通化" to "tong-hua", "白山" to "bai-shan", "松原" to "song-yuan", "白城" to "bai-cheng",
        "延边" to "yan-bian", "榆树" to "yu-shu", "德惠" to "de-hui", "公主岭" to "gong-zhu-ling",
        "蛟河" to "jiao-he", "桦甸" to "hua-dian", "舒兰" to "shu-lan", "磐石" to "pan-shi",
        "双辽" to "shuang-liao", "梅河口" to "mei-he-kou", "集安" to "ji-an", "临江" to "lin-jiang",
        "洮南" to "tao-nan", "大安" to "da-an", "延吉" to "yan-ji", "图们" to "tu-men",
        "敦化" to "dun-hua", "珲春" to "hun-chun", "龙井" to "long-jing", "和龙" to "he-long",
        "扶余" to "fu-yu",
        // 贵州
        "贵阳" to "gui-yang", "六盘水" to "liu-pan-shui", "遵义" to "zun-yi", "安顺" to "an-shun",
        "毕节" to "bi-jie", "铜仁" to "tong-ren", "黔西南" to "qian-xi-nan", "黔东南" to "qian-dong-nan",
        "黔南" to "qian-nan", "清镇" to "qing-zhen", "赤水" to "chi-shui", "仁怀" to "ren-huai",
        "盘州" to "pan-zhou", "凯里" to "kai-li", "都匀" to "du-yun", "福泉" to "fu-quan",
        "兴义" to "xing-yi", "兴仁" to "xing-ren",
        // 内蒙古
        "呼和浩特" to "hu-he-hao-te", "包头" to "bao-tou", "乌海" to "wu-hai", "赤峰" to "chi-feng",
        "通辽" to "tong-liao", "鄂尔多斯" to "e-er-duo-si", "呼伦贝尔" to "hu-lun-bei-er", "巴彦淖尔" to "ba-yan-nao-er",
        "乌兰察布" to "wu-lan-cha-bu", "兴安盟" to "xing-an-meng", "锡林郭勒" to "xi-lin-guo-le", "阿拉善" to "a-la-shan",
        "霍林郭勒" to "huo-lin-guo-le", "满洲里" to "man-zhou-li", "牙克石" to "ya-ke-shi", "扎兰屯" to "zha-lan-tun",
        "额尔古纳" to "e-er-gu-na", "根河" to "gen-he", "丰镇" to "feng-zhen", "乌兰浩特" to "wu-lan-hao-te",
        "阿尔山" to "a-er-shan", "二连浩特" to "er-lian-hao-te", "锡林浩特" to "xi-lin-hao-te",
        // 新疆
        "乌鲁木齐" to "wu-lu-mu-qi", "克拉玛依" to "ke-la-ma-yi", "吐鲁番" to "tu-lu-fan", "哈密" to "ha-mi",
        "昌吉" to "chang-ji", "博尔塔拉" to "bo-er-ta-la", "巴音郭楞" to "ba-yin-guo-leng", "阿克苏" to "a-ke-su",
        "克孜勒苏" to "ke-zi-le-su", "喀什" to "ka-shi", "和田" to "he-tian", "伊犁" to "yi-li",
        "塔城" to "ta-cheng", "阿勒泰" to "a-le-tai", "石河子" to "shi-he-zi", "阿拉尔" to "a-la-er",
        "图木舒克" to "tu-mu-shu-ke", "五家渠" to "wu-jia-qu", "北屯" to "bei-tun", "铁门关" to "tie-men-guan",
        "双河" to "shuang-he", "可克达拉" to "ke-ke-da-la", "昆玉" to "kun-yu", "胡杨河" to "hu-yang-he",
        "新星" to "xin-xing", "白杨" to "bai-yang", "库尔勒" to "ku-er-le", "奎屯" to "kui-tun",
        "霍尔果斯" to "huo-er-guo-si", "乌苏" to "wu-su", "沙湾" to "sha-wan",
        // 甘肃
        "兰州" to "lan-zhou", "嘉峪关" to "jia-yu-guan", "金昌" to "jin-chang", "白银" to "bai-yin",
        "天水" to "tian-shui", "武威" to "wu-wei", "张掖" to "zhang-ye", "平凉" to "ping-liang",
        "酒泉" to "jiu-quan", "庆阳" to "qing-yang", "定西" to "ding-xi", "陇南" to "long-nan",
        "临夏" to "lin-xia", "甘南" to "gan-nan", "玉门" to "yu-men", "敦煌" to "dun-huang",
        "合作" to "he-zuo", "华亭" to "hua-ting",
        // 海南
        "海口" to "hai-kou", "三亚" to "san-ya", "三沙" to "san-sha", "儋州" to "dan-zhou",
        "五指山" to "wu-zhi-shan", "琼海" to "qiong-hai", "文昌" to "wen-chang", "万宁" to "wan-ning",
        "东方" to "dong-fang", "定安" to "ding-an", "屯昌" to "tun-chang", "澄迈" to "cheng-mai",
        "临高" to "lin-gao", "白沙" to "bai-sha", "昌江" to "chang-jiang", "乐东" to "le-dong",
        "陵水" to "ling-shui", "保亭" to "bao-ting", "琼中" to "qiong-zhong",
        // 宁夏
        "银川" to "yin-chuan", "石嘴山" to "shi-zui-shan", "吴忠" to "wu-zhong", "固原" to "gu-yuan",
        "中卫" to "zhong-wei", "灵武" to "ling-wu", "青铜峡" to "qing-tong-xia",
        // 青海
        "西宁" to "xi-ning", "海东" to "hai-dong", "海北" to "hai-bei", "黄南" to "huang-nan",
        "海南" to "hai-nan", "果洛" to "guo-luo", "玉树" to "yu-shu", "海西" to "hai-xi",
        "格尔木" to "ge-er-mu", "德令哈" to "de-ling-ha", "茫崖" to "mang-ya",
        // 西藏
        "拉萨" to "la-sa", "日喀则" to "ri-ka-ze", "昌都" to "chang-du", "林芝" to "lin-zhi",
        "山南" to "shan-nan", "那曲" to "na-qu", "阿里" to "a-li",
        // 港澳台
        "香港" to "xiang-gang", "澳门" to "ao-men", "台北" to "tai-bei", "高雄" to "gao-xiong",
        "台中" to "tai-zhong", "台南" to "tai-nan", "新北" to "xin-bei", "桃园" to "tao-yuan",
        "新竹" to "xin-zhu", "基隆" to "ji-long", "嘉义" to "jia-yi", "台湾" to "tai-wan"
    )

    /** 城市名（可带「市/县」等后缀）→ 音节分隔的全拼；未收录返回 null */
    fun segmentOf(cityName: String): String? {
        val trimmed = cityName.trim()
        if (trimmed.isEmpty()) return null
        return SEGMENTS[ProvinceCityCatalog.cleanCityName(trimmed)] ?: SEGMENTS[trimmed]
    }

    /** 城市名 → 连写全拼（玉林 → yulin）；未收录返回 null */
    fun fullPinyin(cityName: String): String? = segmentOf(cityName)?.let(::joinSegments)

    /** 城市名 → 首字母串（玉林 → yl）；未收录返回 null */
    fun initialsOf(cityName: String): String? = segmentOf(cityName)?.let(::initialsFromSegments)

    /** 音节分隔全拼 → 连写全拼（yulin 这种）；给索引内和索引外（网络城市自带 pinyin 字段）共用 */
    fun joinSegments(segments: String): String = segments.replace("-", "")

    /** 音节分隔全拼 → 首字母串（yl）；zh/ch/sh 只取首字母 z/c/s */
    fun initialsFromSegments(segments: String): String = buildString {
        for (token in segments.split('-')) {
            val head = token.firstOrNull()
            if (head != null) append(head)
        }
    }

    /** 收录条目数（覆盖率测试用） */
    val size: Int get() = SEGMENTS.size
}

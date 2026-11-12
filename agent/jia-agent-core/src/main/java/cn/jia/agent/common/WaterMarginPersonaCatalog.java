package cn.jia.agent.common;

import cn.jia.agent.entity.AgentPersonaEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checked-in immutable public metadata for the 108 Water Margin builtin personas. */
public final class WaterMarginPersonaCatalog {
    public static final String MODEL_ASSIGNMENT_VERSION = "water-margin-model-assignment-v1";
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("songjiang", 1, "天魁星", "宋江", "及时雨", "gpt-6-astra"),
            new Definition("lujunyi", 2, "天罡星", "卢俊义", "玉麒麟", "gpt-6.1-sol"),
            new Definition("wuyong", 3, "天机星", "吴用", "智多星", "gpt-6-astra"),
            new Definition("gongsunsheng", 4, "天闲星", "公孙胜", "入云龙", "gpt-6-astra"),
            new Definition("guansheng", 5, "天勇星", "关胜", "大刀", "gpt-6.1-sol"),
            new Definition("linchong", 6, "天雄星", "林冲", "豹子头", "gpt-6.1-sol"),
            new Definition("qinming", 7, "天猛星", "秦明", "霹雳火", "gpt-6-sol"),
            new Definition("huyanzhuo", 8, "天威星", "呼延灼", "双鞭", "gpt-6.1-sol"),
            new Definition("huarong", 9, "天英星", "花荣", "小李广", "gpt-6.1-terra"),
            new Definition("chaijin", 10, "天贵星", "柴进", "小旋风", "gpt-6.1-sol"),
            new Definition("liying", 11, "天富星", "李应", "扑天雕", "gpt-6-sol"),
            new Definition("zhutong", 12, "天满星", "朱仝", "美髯公", "gpt-6-sol"),
            new Definition("luzhishen", 13, "天孤星", "鲁智深", "花和尚", "gpt-6-sol"),
            new Definition("wusong", 14, "天伤星", "武松", "行者", "gpt-6-sol"),
            new Definition("dongping", 15, "天立星", "董平", "双枪将", "gpt-6-sol"),
            new Definition("zhangqing", 16, "天捷星", "张清", "没羽箭", "gpt-6-luna"),
            new Definition("yangzhi", 17, "天暗星", "杨志", "青面兽", "gpt-6.1-terra"),
            new Definition("xuning", 18, "天佑星", "徐宁", "金枪手", "gpt-6.1-terra"),
            new Definition("suochao", 19, "天空星", "索超", "急先锋", "gpt-6-luna"),
            new Definition("daizong", 20, "天速星", "戴宗", "神行太保", "gpt-6-luna"),
            new Definition("liutang", 21, "天异星", "刘唐", "赤发鬼", "gpt-6-luna"),
            new Definition("likui", 22, "天杀星", "李逵", "黑旋风", "gpt-6-luna"),
            new Definition("shijin", 23, "天微星", "史进", "九纹龙", "gpt-6-sol"),
            new Definition("muhong", 24, "天究星", "穆弘", "没遮拦", "gpt-6-sol"),
            new Definition("leiheng", 25, "天退星", "雷横", "插翅虎", "gpt-5.6-sol"),
            new Definition("lijun", 26, "天寿星", "李俊", "混江龙", "gpt-6.1-sol"),
            new Definition("ruanxiaoer", 27, "天剑星", "阮小二", "立地太岁", "gpt-6-sol"),
            new Definition("zhangheng", 28, "天平星", "张横", "船火儿", "gpt-5.6-sol"),
            new Definition("ruanxiaowu", 29, "天罪星", "阮小五", "短命二郎", "gpt-5.6-sol"),
            new Definition("zhangshun", 30, "天损星", "张顺", "浪里白条", "gpt-6.1-terra"),
            new Definition("ruanxiaoqi", 31, "天败星", "阮小七", "活阎罗", "gpt-6-luna"),
            new Definition("yangxiong", 32, "天牢星", "杨雄", "病关索", "gpt-5.6-sol"),
            new Definition("shixiu", 33, "天慧星", "石秀", "拼命三郎", "gpt-6-sol"),
            new Definition("xiezhen", 34, "天暴星", "解珍", "两头蛇", "gpt-5.6-terra"),
            new Definition("xiebao", 35, "天哭星", "解宝", "双尾蝎", "gpt-5.6-terra"),
            new Definition("yanqing", 36, "天巧星", "燕青", "浪子", "gpt-6.1-sol"),
            new Definition("zhuwu", 37, "地魁星", "朱武", "神机军师", "gpt-6.1-sol"),
            new Definition("huangxin", 38, "地煞星", "黄信", "镇三山", "gpt-5.6-sol"),
            new Definition("sunli", 39, "地勇星", "孙立", "病尉迟", "gpt-6-sol"),
            new Definition("xuanzan", 40, "地杰星", "宣赞", "丑郡马", "gpt-5.6-sol"),
            new Definition("haosiwen", 41, "地雄星", "郝思文", "井木犴", "gpt-5.6-sol"),
            new Definition("hantao", 42, "地威星", "韩滔", "百胜将", "gpt-5.6-sol"),
            new Definition("pengqi", 43, "地英星", "彭玘", "天目将", "gpt-5.6-sol"),
            new Definition("shantinggui", 44, "地奇星", "单廷珪", "圣水将", "gpt-6.1-terra"),
            new Definition("weidingguo", 45, "地猛星", "魏定国", "神火将", "gpt-6.1-terra"),
            new Definition("xiaorang", 46, "地文星", "萧让", "圣手书生", "gpt-6.1-terra"),
            new Definition("peixuan", 47, "地正星", "裴宣", "铁面孔目", "gpt-6.1-terra"),
            new Definition("oupeng", 48, "地阔星", "欧鹏", "摩云金翅", "gpt-5.6-sol"),
            new Definition("dengfei", 49, "地阖星", "邓飞", "火眼狻猊", "gpt-5.6-sol"),
            new Definition("yanshun", 50, "地强星", "燕顺", "锦毛虎", "gpt-5.6-sol"),
            new Definition("yanglin", 51, "地暗星", "杨林", "锦豹子", "gpt-5.6-luna"),
            new Definition("lingzhen", 52, "地轴星", "凌振", "轰天雷", "gpt-6.1-terra"),
            new Definition("jiangjing", 53, "地会星", "蒋敬", "神算子", "gpt-6.1-terra"),
            new Definition("lvfang", 54, "地佐星", "吕方", "小温侯", "gpt-5.6-terra"),
            new Definition("guosheng", 55, "地佑星", "郭盛", "赛仁贵", "gpt-5.6-terra"),
            new Definition("andaoquan", 56, "地灵星", "安道全", "神医", "gpt-6.1-terra"),
            new Definition("huangfuduan", 57, "地兽星", "皇甫端", "紫髯伯", "gpt-6.1-terra"),
            new Definition("wangying", 58, "地微星", "王英", "矮脚虎", "gpt-5.6-terra"),
            new Definition("husanniang", 59, "地慧星", "扈三娘", "一丈青", "gpt-6-sol"),
            new Definition("baoxu", 60, "地暴星", "鲍旭", "丧门神", "gpt-5.6-terra"),
            new Definition("fanrui", 61, "地然星", "樊瑞", "混世魔王", "gpt-6.1-terra"),
            new Definition("kongming", 62, "地猖星", "孔明", "毛头星", "gpt-5.6-luna"),
            new Definition("kongliang", 63, "地狂星", "孔亮", "独火星", "gpt-5.6-luna"),
            new Definition("xiangchong", 64, "地飞星", "项充", "八臂哪吒", "gpt-5.6-terra"),
            new Definition("ligun", 65, "地走星", "李衮", "飞天大圣", "gpt-5.6-terra"),
            new Definition("jindajian", 66, "地巧星", "金大坚", "玉臂匠", "gpt-6.1-terra"),
            new Definition("malin", 67, "地明星", "马麟", "铁笛仙", "gpt-5.6-terra"),
            new Definition("tongwei", 68, "地进星", "童威", "出洞蛟", "gpt-5.6-luna"),
            new Definition("tongmeng", 69, "地退星", "童猛", "翻江蜃", "gpt-5.6-luna"),
            new Definition("mengkang", 70, "地满星", "孟康", "玉幡竿", "gpt-6.1-terra"),
            new Definition("houjian", 71, "地遂星", "侯健", "通臂猿", "gpt-5.6-terra"),
            new Definition("chenda", 72, "地周星", "陈达", "跳涧虎", "gpt-5.6-terra"),
            new Definition("yangchun", 73, "地隐星", "杨春", "白花蛇", "gpt-5.6-terra"),
            new Definition("zhengtianshou", 74, "地异星", "郑天寿", "白面郎君", "gpt-5.6-terra"),
            new Definition("taozongwang", 75, "地理星", "陶宗旺", "九尾龟", "gpt-5.6-terra"),
            new Definition("songqing", 76, "地俊星", "宋清", "铁扇子", "gpt-5.6-sol"),
            new Definition("yuehe", 77, "地乐星", "乐和", "铁叫子", "gpt-6-luna"),
            new Definition("gongwang", 78, "地捷星", "龚旺", "花项虎", "gpt-5.6-terra"),
            new Definition("dingdesun", 79, "地速星", "丁得孙", "中箭虎", "gpt-5.6-terra"),
            new Definition("muchun", 80, "地镇星", "穆春", "小遮拦", "gpt-5.6-luna"),
            new Definition("caozheng", 81, "地稽星", "曹正", "操刀鬼", "gpt-5.6-terra"),
            new Definition("songwan", 82, "地魔星", "宋万", "云里金刚", "gpt-5.6-luna"),
            new Definition("duqian", 83, "地妖星", "杜迁", "摸着天", "gpt-5.6-luna"),
            new Definition("xueyong", 84, "地幽星", "薛永", "病大虫", "gpt-5.6-terra"),
            new Definition("shien", 85, "地伏星", "施恩", "金眼彪", "gpt-5.6-sol"),
            new Definition("lizhong", 86, "地僻星", "李忠", "打虎将", "gpt-5.6-terra"),
            new Definition("zhoutong", 87, "地空星", "周通", "小霸王", "gpt-5.6-luna"),
            new Definition("tanglong", 88, "地孤星", "汤隆", "金钱豹子", "gpt-6.1-terra"),
            new Definition("duxing", 89, "地全星", "杜兴", "鬼脸儿", "gpt-5.6-sol"),
            new Definition("zouyuan", 90, "地短星", "邹渊", "出林龙", "gpt-5.6-terra"),
            new Definition("zourun", 91, "地角星", "邹润", "独角龙", "gpt-5.6-terra"),
            new Definition("zhugui", 92, "地囚星", "朱贵", "旱地忽律", "gpt-6-luna"),
            new Definition("zhufu", 93, "地藏星", "朱富", "笑面虎", "gpt-5.6-sol"),
            new Definition("caifu", 94, "地平星", "蔡福", "铁臂膊", "gpt-5.6-terra"),
            new Definition("caiqing", 95, "地损星", "蔡庆", "一枝花", "gpt-5.6-luna"),
            new Definition("lili", 96, "地奴星", "李立", "催命判官", "gpt-5.6-luna"),
            new Definition("liyun", 97, "地察星", "李云", "青眼虎", "gpt-5.6-terra"),
            new Definition("jiaoting", 98, "地恶星", "焦挺", "没面目", "gpt-5.6-terra"),
            new Definition("shiyong", 99, "地丑星", "石勇", "石将军", "gpt-5.6-luna"),
            new Definition("sunxin", 100, "地数星", "孙新", "小尉迟", "gpt-5.6-sol"),
            new Definition("gudasao", 101, "地阴星", "顾大嫂", "母大虫", "gpt-6-sol"),
            new Definition("zhangqing_gardener", 102, "地刑星", "张青", "菜园子", "gpt-5.6-sol"),
            new Definition("sunerniang", 103, "地壮星", "孙二娘", "母夜叉", "gpt-6-sol"),
            new Definition("wangdingliu", 104, "地劣星", "王定六", "活闪婆", "gpt-5.6-luna"),
            new Definition("yubaosi", 105, "地健星", "郁保四", "险道神", "gpt-5.6-luna"),
            new Definition("baisheng", 106, "地耗星", "白胜", "白日鼠", "gpt-5.6-luna"),
            new Definition("shiqian", 107, "地贼星", "时迁", "鼓上蚤", "gpt-6-luna"),
            new Definition("duanjingzhu", 108, "地狗星", "段景住", "金毛犬", "gpt-5.6-terra")
    );
    private static final Map<String, Definition> BY_CODE = byCode();

    private WaterMarginPersonaCatalog() { }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static List<AgentPersonaEntity> publicPersonas() {
        return DEFINITIONS.stream().map(Definition::toPublicEntity).toList();
    }

    /** Null scope alone is not publication authority: only exact checked-in definitions are public. */
    public static boolean isPublicDefinition(AgentPersonaEntity persona) {
        return persona != null && persona.getTenantId() == null && persona.getClientId() == null
                && persona.equals(publicPersona(persona.getPersonaCode()));
    }

    public static Definition definition(String personaCode) {
        return personaCode == null ? null : BY_CODE.get(personaCode);
    }

    public static AgentPersonaEntity publicPersona(String personaCode) {
        Definition definition = personaCode == null ? null : BY_CODE.get(personaCode);
        return definition == null ? null : definition.toPublicEntity();
    }

    private static Map<String, Definition> byCode() {
        LinkedHashMap<String, Definition> definitions = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            if (definitions.putIfAbsent(definition.code(), definition) != null) {
                throw new IllegalStateException("duplicate builtin persona code: " + definition.code());
            }
        }
        return Map.copyOf(definitions);
    }

    public record Definition(String code, int rankNo, String starName, String name, String title, String modelId) {
        public String visualConfig() {
            int x = (rankNo - 1) % 6;
            int y = (rankNo - 1) / 6;
            return "{\"gridX\":" + x + ",\"gridY\":" + y + "}";
        }

        public String abilities() {
            if ("songjiang".equals(code)) {
                return "[\"coordination\",\"dispatch\",\"planning\",\"briefing\",\"task_management\"]";
            }
            return "[]";
        }

        public String personality() {
            return title + "，梁山第" + rankNo + "位好汉。";
        }

        public String speakingStyle() {
            return rankNo <= 36 ? "沉稳果决，重义守信。" : "直率利落，听令而行。";
        }

        public String background() {
            return starName + "，" + name + "，绰号" + title + "。";
        }

        public int power() {
            return score(rankNo, 72);
        }

        public int intelligence() {
            return score(109 - rankNo, 70);
        }

        public int leadership() {
            return score(rankNo, 66);
        }

        public boolean systemAgent() {
            return "songjiang".equals(code);
        }

        public AgentPersonaEntity toPublicEntity() {
            AgentPersonaEntity entity = new AgentPersonaEntity();
            entity.setPersonaCode(code);
            entity.setRankNo(rankNo);
            entity.setStarName(starName);
            entity.setName(name);
            entity.setTitle(title);
            entity.setAvatar("");
            entity.setVisualConfig(visualConfig());
            entity.setAbilities(abilities());
            entity.setPersonality(personality());
            entity.setSpeakingStyle(speakingStyle());
            entity.setBackground(background());
            entity.setPower(power());
            entity.setIntelligence(intelligence());
            entity.setLeadership(leadership());
            entity.setActive(true);
            entity.setSystemAgent(systemAgent());
            // A checked-in platform definition is not owned by any business tenant.
            entity.setTenantId(null);
            entity.setClientId(null);
            return entity;
        }

        private static int score(int value, int base) {
            return Math.max(35, Math.min(98, base + (value % 24)));
        }
    }
}

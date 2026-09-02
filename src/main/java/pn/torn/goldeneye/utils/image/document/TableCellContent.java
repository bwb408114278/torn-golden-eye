package pn.torn.goldeneye.utils.image.document;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 表格单元格的受控内容模型，渲染中立的有限组合。
 * <p>
 * 该层级只表达"纯文本、名称加徽章、三段式"三种受控展示语义，不是HTML模型；
 * 实现方禁止携带HTML、CSS class、URL、属性或任意标签，标签与样式只能由HTML渲染器按类型映射生成。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.09.01
 */
public sealed interface TableCellContent permits TableCellContent.PlainText,
        TableCellContent.BadgeText, TableCellContent.ThreePartText {

    /**
     * 按内容类型组合出可读纯文本，仅供兼容断言或日志使用。
     *
     * @return 可读组合文本
     */
    default String readableText() {
        return switch (this) {
            case PlainText(String text) -> text;
            case BadgeText(String primaryText, List<Badge> badges) -> primaryText + " " + badges.stream()
                    .map(Badge::text)
                    .collect(Collectors.joining(" "));
            case ThreePartText(String leadingText, String centerText, String trailingText) ->
                    Stream.of(leadingText, centerText, trailingText)
                            .filter(text -> !text.isEmpty())
                            .collect(Collectors.joining(" "));
        };
    }

    /**
     * 单段纯文本内容。
     *
     * @param text 单元格文本，不能为null，允许为空字符串
     */
    record PlainText(String text) implements TableCellContent {

        /**
         * 创建并校验纯文本内容。
         */
        public PlainText {
            Objects.requireNonNull(text, "text不能为null");
        }
    }

    /**
     * 主文本加一个或多个状态徽章的内容。
     *
     * @param primaryText 主文本，不能为null
     * @param badges     状态徽章，不能为null且至少一个；无徽章时应使用{@link PlainText}
     */
    record BadgeText(String primaryText, List<Badge> badges) implements TableCellContent {

        /**
         * 创建并校验徽章内容。
         */
        public BadgeText {
            Objects.requireNonNull(primaryText, "primaryText不能为null");
            Objects.requireNonNull(badges, "badges不能为null");
            if (badges.isEmpty()) {
                throw new IllegalArgumentException("badges不能为空");
            }
            badges = List.copyOf(badges);
        }
    }

    /**
     * 状态徽章的文本与受控色调。
     *
     * @param text     徽章文本，不能为null且不能为空白
     * @param badgeTone 徽章受控色调，不能为null
     */
    record Badge(String text, TableCellBadgeToneEnum badgeTone) {

        /**
         * 创建并校验徽章。
         */
        public Badge {
            Objects.requireNonNull(text, "text不能为null");
            Objects.requireNonNull(badgeTone, "badgeTone不能为null");
            if (text.isBlank()) {
                throw new IllegalArgumentException("text不能为空白");
            }
        }
    }

    /**
     * 左、中、右三段式内容，用于岗位行的状态Emoji、岗位名和成功率。
     *
     * @param leadingText  左侧文本，不能为null，允许为空字符串
     * @param centerText   中间文本，不能为null且不能为空白
     * @param trailingText 右侧文本，不能为null，允许为空字符串
     */
    record ThreePartText(String leadingText, String centerText, String trailingText)
            implements TableCellContent {

        /**
         * 创建并校验三段式内容。
         */
        public ThreePartText {
            Objects.requireNonNull(leadingText, "leadingText不能为null");
            Objects.requireNonNull(centerText, "centerText不能为null");
            Objects.requireNonNull(trailingText, "trailingText不能为null");
            if (centerText.isBlank()) {
                throw new IllegalArgumentException("centerText不能为空白");
            }
        }
    }
}

package nl.pim16aap2.animatedarchitecture.spigot.core.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.republicraft.rcui.api.MessageBundle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeeMessagesTest {
    @Test void allFeeMessagesHaveDefaultsAndPlayerDataCannotInjectFormatting() throws Exception {
        var yaml = new YamlConfiguration();
        try(var stream=getClass().getResourceAsStream("/fees-messages.yml")) {
            assertNotNull(stream);
            yaml.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
        }
        for(String key:List.of("price-changed","confirmation-required","receipt-required","paid",
                "payment-rejected","refunded","refund-pending","review-required","failed","permission-denied",
                "storage-unavailable","review-page","review-entry","resolution-failed","resolution-recorded",
                "resolution-rejected","invalid-input","usage"))
            assertTrue(yaml.isString(key),key);
        MessageBundle bundle = new MessageBundle() {
            public String namespace() { return "rcdoors-fees"; }
            public Component component(String key,TagResolver... arguments) {
                return MiniMessage.miniMessage().deserialize(yaml.getString(key),arguments);
            }
            public void broadcast(String key,TagResolver... arguments) { throw new UnsupportedOperationException(); }
        };
        var messages = new FeeMessages(bundle);
        var sender = mock(CommandSender.class,withSettings().extraInterfaces(net.kyori.adventure.audience.Audience.class));
        messages.send(sender,"invalid-input","detail","<red>operator-controlled text</red>");
        var component = ArgumentCaptor.forClass(Component.class);
        verify((net.kyori.adventure.audience.Audience)sender).sendMessage(component.capture());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(component.getValue())
            .contains("<red>operator-controlled text</red>"));
        assertThrows(IllegalArgumentException.class,()->messages.send(sender,"paid","amount"));
    }
}

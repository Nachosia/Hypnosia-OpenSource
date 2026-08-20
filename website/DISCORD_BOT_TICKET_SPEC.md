# Discord Bot — Ticket System Integration Spec

## Overview
The website backend (Hono + tRPC + Drizzle + MySQL) now has a full ticket system.
The Kotlin Discord bot must integrate with it to provide two-way sync.

## Database Tables

### `tickets`
| Column | Type | Notes |
|--------|------|-------|
| id | INT PK AI | Ticket ID |
| user_id | INT | Site user ID (nullable if created from Discord) |
| discord_user_id | VARCHAR(64) | Discord user ID |
| title | VARCHAR(255) | Ticket subject |
| description | TEXT | Initial description |
| category | VARCHAR(32) | e.g. technical, payment, account, bug, other |
| status | ENUM('open','closed') | |
| assigned_admin_id | INT | Admin who took the ticket |
| created_at | TIMESTAMP | |
| closed_at | TIMESTAMP | |
| closed_by | INT | Admin who closed |
| discord_channel_id | VARCHAR(64) | Linked Discord channel |

### `ticket_messages`
| Column | Type | Notes |
|--------|------|-------|
| id | INT PK AI | |
| ticket_id | INT FK | |
| sender_type | ENUM('user','admin','system') | |
| sender_id | INT | Site user ID |
| sender_discord_id | VARCHAR(64) | If sent from Discord |
| sender_name | VARCHAR(255) | Display name |
| content | TEXT | |
| has_attachment | ENUM('true','false') | |
| attachments | JSON | Array of {url, name, size} |
| created_at | TIMESTAMP | |
| discord_message_id | VARCHAR(64) | For sync |

## Bot Actions

### 1. Action Button / Context Menu: "Написать в Support"

Register a **User Context Menu** command (appears on right-click user):
```kotlin
// JDA / JDA5 example
Commands.context(Command.Type.USER, "Написать в Support")
    .setDefaultPermissions(DefaultMemberPermissions.DISABLED) // or enabled for all
```

On interaction (Modal open):
```kotlin
// Show modal with 2 text inputs
val titleInput = TextInput.create("ticket_title", "Тема", TextInputStyle.SHORT)
    .setPlaceholder("Кратко опишите проблему")
    .setMinLength(3)
    .setMaxLength(100)
    .build()

val descInput = TextInput.create("ticket_description", "Описание", TextInputStyle.PARAGRAPH)
    .setPlaceholder("Подробное описание проблемы...")
    .setMinLength(10)
    .setMaxLength(2000)
    .build()

event.replyModal(
    Modal.create("ticket_create_modal", "Новый тикет")
        .addComponents(ActionRow.of(titleInput), ActionRow.of(descInput))
        .build()
).queue()
```

On modal submit:
```kotlin
val title = event.getValue("ticket_title")?.asString ?: return
val description = event.getValue("ticket_description")?.asString ?: return
val user = event.user

// 1. Call internal API to create ticket
val response = httpClient.post("$siteUrl/internal/discord/ticket-create") {
    header("Authorization", "Bearer $serviceToken")
    setBody(json.encodeToString(TicketCreateRequest(
        discordUserId = user.id,
        title = title,
        description = description,
        category = "other", // optionally ask category in modal
    )))
}
val ticket = response.body<TicketCreateResponse>()

// 2. Create Discord channel
val guild = event.guild ?: return
val category = guild.getCategoryById(TICKET_CATEGORY_ID)
val channel = guild.createTextChannel("ticket-${ticket.id}", category)
    .setTopic("Ticket #${ticket.id} | $title | Admin: http://127.0.0.1:3000/admin?tickets")
    .addPermissionOverride(guild.publicRole, emptySet(), setOf(Permission.VIEW_CHANNEL))
    // Add admin role overrides here
    .queue { ch ->
        // 3. Send embed with buttons
        val embed = EmbedBuilder()
            .setTitle("🎫 Ticket #${ticket.id}")
            .setDescription(description)
            .setColor(Color(0x6bb7ff))
            .addField("Author", user.asMention, true)
            .addField("Category", "other", true)
            .addField("Link", "[Admin Panel](http://127.0.0.1:3000/admin?tickets)", false)
            .setTimestamp(Instant.now())
            .build()

        val buttons = ActionRow.of(
            Button.primary("ticket_take:${ticket.id}", "Взять тикет"),
            Button.danger("ticket_close:${ticket.id}", "Закрыть тикет")
        )

        ch.sendMessageEmbeds(embed).setComponents(buttons).queue()
    }
```

### 2. Button Handlers

**"Взять тикет"** button (`ticket_take:{id}`):
```kotlin
val ticketId = event.componentId.split(":")[1].toInt()
httpClient.post("$siteUrl/internal/discord/ticket-assign") {
    header("Authorization", "Bearer $serviceToken")
    setBody(json.encodeToString(TicketAssignRequest(
        ticketId = ticketId,
        adminDiscordId = event.user.id,
        adminName = event.user.name,
    )))
}
event.reply("🎯 **${event.user.name}** взял тикет.").queue()
```

**"Закрыть тикет"** button (`ticket_close:{id}`):
```kotlin
val ticketId = event.componentId.split(":")[1].toInt()
httpClient.post("$siteUrl/internal/discord/ticket-close") {
    header("Authorization", "Bearer $serviceToken")
    setBody(json.encodeToString(TicketCloseRequest(
        ticketId = ticketId,
        closedByDiscordId = event.user.id,
    )))
}
// Delete channel
event.channel.delete().queue()
```

### 3. Message Listener (Discord → Site)

Listen to `onMessageReceived` in ticket channels:
```kotlin
override fun onMessageReceived(event: MessageReceivedEvent) {
    if (event.author.isBot) return
    val channel = event.channel as? TextChannel ?: return
    if (!channel.name.startsWith("ticket-")) return
    
    val ticketId = channel.name.removePrefix("ticket-").toIntOrNull() ?: return
    
    // Download attachments
    val attachments = event.message.attachments.map { att ->
        val bytes = att.proxy.download().get().readAllBytes()
        // Upload to site
        val uploadResponse = httpClient.post("$siteUrl/internal/discord/upload") { ... }
        AttachmentInfo(uploadResponse.url, att.fileName, att.size)
    }
    
    httpClient.post("$siteUrl/internal/discord/ticket-message") {
        header("Authorization", "Bearer $serviceToken")
        setBody(json.encodeToString(TicketMessageRequest(
            ticketId = ticketId,
            senderDiscordId = event.author.id,
            senderName = event.author.name,
            content = event.message.contentRaw,
            attachments = attachments,
        )))
    }
}
```

### 4. Slash Command: `/close`

Alternative to button:
```kotlin
override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
    if (event.name != "close") return
    val channel = event.channel as? TextChannel ?: return
    val ticketId = channel.name.removePrefix("ticket-").toIntOrNull() ?: return
    
    httpClient.post("$siteUrl/internal/discord/ticket-close") { ... }
    event.reply("🔒 Тикет закрыт. Канал удалён.").queue()
    channel.delete().queue()
}
```

## Internal API for Bot

Base URL: `http://127.0.0.1:3000/internal/discord`
Auth header: `Authorization: Bearer <SERVICE_TOKEN>`

### `POST /ticket-create`
Request:
```json
{
  "discordUserId": "123456789",
  "title": "Problem title",
  "description": "Detailed description",
  "category": "technical",
  "discordChannelId": "987654321"
}
```
Response: `{ "id": 42, "discordChannelId": "987654321" }`

### `POST /ticket-message`
Request:
```json
{
  "ticketId": 42,
  "senderDiscordId": "123456789",
  "senderName": "UserName",
  "content": "Message text",
  "attachments": [
    { "url": "http://127.0.0.1:3000/uploads/tickets/42/...", "name": "file.png", "size": 12345 }
  ]
}
```

### `POST /ticket-close`
Request:
```json
{
  "ticketId": 42,
  "closedByDiscordId": "123456789"
}
```

### `POST /ticket-assign`
Request:
```json
{
  "ticketId": 42,
  "adminDiscordId": "123456789",
  "adminName": "AdminName"
}
```

### `GET /ticket-by-channel/:channelId`
Response: Full ticket object or 404

## DM Notifications (handled by site)

The site backend now sends DM notifications automatically:
- **Ticket created** → DM to user with confirmation
- **Admin assigned** → DM to user
- **Admin replied** → DM to user with response preview
- **Ticket closed** → DM to user

No need to implement DM logic in the bot — the site handles it via Discord REST API.

## Environment Variables

```
NACHOSIA_SITE_URL=http://127.0.0.1:3000
NACHOSIA_SERVICE_TOKEN=<shared secret>
DISCORD_TICKET_CATEGORY_ID=1510176942664712222
```

## Channel Naming
- Format: `ticket-{id}` (e.g. `ticket-42`)
- Topic: `Ticket #42 | Admin: http://127.0.0.1:3000/admin?tickets`

## Permissions Setup (User Configurable)
- Read admin role IDs from config or DB
- `@everyone` — deny VIEW_CHANNEL
- Admin roles — allow VIEW_CHANNEL, SEND_MESSAGES, ATTACH_FILES
- Bot — allow all

## File Attachments
- Max 5 MB per file (both directions)
- Discord allows up to 25 MB, but site limits to 5 MB
- When bot forwards file from Discord → Site:
  - Download attachment bytes
  - POST to site's upload endpoint (or base64 in internal API)
  - Store returned URL in attachments array

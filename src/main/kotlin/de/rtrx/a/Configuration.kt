package de.rtrx.a

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.notEmptyOr
import com.uchuhimo.konf.source.yaml
import java.io.File
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption


lateinit var config: Config

fun initConfig(path: String?){
    config = Config { addSpec(RedditSpec); addSpec(DBSpec); addSpec(LoggingSpec)}.from.yaml.watchFile(
        run {
            val filePath = path.orEmpty().notEmptyOr("${System.getProperty("user.dir")}/config.yml")
            val file = File(filePath)
            if(file.createNewFile() || Files.size(file.toPath()) == 0L){
                val inputStream = RedditSpec::class.java.getResourceAsStream("/config.yml")
                Files.copy(inputStream, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING )
            }
            filePath
        })
}

object RedditSpec: ConfigSpec("reddit") {
    val subreddit by required<String>()

    object credentials: ConfigSpec("credentials"){
        val username by required<String>()
        val clientID by required<String>()
        val clientSecret by required<String>()
        val password by required<String>()
        val operatorUsername by required<String>()
        val appID by required<String>()
    }
    object submissions : ConfigSpec("submissions"){
        val maxTimeDistance by optional(60*60*1000L)
        val limit by optional(25)
        val waitIntervall by optional(5*1000L)
    }

    object messages: ConfigSpec("messages"){
        object sent: ConfigSpec("sent") {
            val timeSaved by optional(900 * 1000 * 4L)
            val maxTimeDistance by optional(15 * 60 * 1000L)
            val limit by optional(25)
            val waitIntervall by optional(5*1000L)
            val subject by optional("Please explain what is unexpected in your submission")
            val body by optional(
                    "Hi, I've noticed you submitted [this](%{Submission}) to r/%{subreddit}. \n" +
                            "Please reply to this message with a short explaination of why your post fits to this subreddit.\n" +
                            "Your reply will be posted by me in the comments section of your post.\n " +
                            "If you do not reply to this within %{MinutesUntilRemoval} minutes, your post will be removed" +
                            "until you provide an explanation.\n" +
                            "BEE-BOOOP I'M A BOT. If you have any questions regarding the moderation guidelines please contact the moderators via modmail.\n" +
                            "If you want to learn more about this bot contact /u/Artraxaron, my creator"
            )
        }
        object unread: ConfigSpec("unread"){
            val maxAge by optional(5*60*1000L)
            val maxTimeDistance by optional(5*60*1000L)
            val waitIntervall by optional(5000L)
            val limit by optional(25)
        }
    }

    object scoring: ConfigSpec("scoring"){
        val timeUntilRemoval by optional(60*1000L)
        val commentBody by optional("This is why it's supposed to be unexpected: \n %{Reason}")
    }

    object checks: ConfigSpec("checks"){
        val every by optional(1*60*1000L)
        val forTimes by optional(8)
    }

}

object DBSpec: ConfigSpec("DB"){
    val username by required<String>()
    val password by optional("")
    val address by required<String>()
    val db by required<String>()
}
object LoggingSpec: ConfigSpec("Logging") {
    val logLevel by optional("ERROR")
}




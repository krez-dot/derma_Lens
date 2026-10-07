package com.dermalens.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dermalens.app.ui.LocalAppSettings

/** A cited reference page: what to show, and where it opens. */
data class SourceLink(val label: String, val url: String)

/**
 * Possible causes per detectable condition, shown on Scan Result. Each line was checked against
 * the pages in [causeSources] for that condition (2026-10-05) -- keep the two in step: a cause
 * with no source behind it doesn't belong here.
 */
val conditionCauses: Map<String, List<String>> = mapOf(
    "Acne Vulgaris" to listOf(
        "Pores clogged by oil and dead skin; trapped bacteria trigger an immune reaction that causes pimples",
        "Hormonal changes: puberty, menstrual periods, pregnancy, birth control pills",
        "Family history",
        "Some medicines, such as steroids, testosterone, estrogen and phenytoin",
        "Oily makeup, skin care and hair products (e.g. pomade)",
        "Touching, rubbing or resting on the skin",
        "May make it worse: stress, smoking and too little sleep; diets high in sugar or dairy are a possible but debated link"
    ),
    "Eczema" to listOf(
        "Gene changes that weaken the skin barrier, so skin loses moisture",
        "An overactive immune system causing inflammation in the skin",
        "Family history of eczema, asthma or hay fever",
        "Soaps, detergents, fragrances and certain fabrics",
        "Heat or temperature changes, very dry skin, tobacco smoke and air pollution",
        "Allergens such as pets, pollen, house dust mites and certain foods",
        "Skin infections, stress and hormonal changes such as pregnancy"
    ),
    "Melasma" to listOf(
        "Sunlight (both UV and visible light) and tanning beds",
        "Hormones: pregnancy, birth control pills, hormonal IUDs or implants, hormone replacement therapy",
        "Family history",
        "More common in medium to brown skin tones, including people of Asian heritage",
        "Some medicines (e.g. anti-seizure drugs) and perfumed soaps or cosmetics that cause a reaction to light",
        "Thyroid disease; some research also links stress"
    ),
    "Tinea" to listOf(
        "Fungi that thrive in warm, humid places",
        "Skin contact with an infected person, or with an infected dog, cat or farm animal",
        "Sharing towels, combs, clothing or sports equipment",
        "Damp surfaces such as shower floors, locker rooms and indoor pools",
        "Hot, humid weather and heavy sweating",
        "Obesity and diabetes raise the risk"
    ),
    "Warts" to listOf(
        "Human papillomavirus (HPV) -- there are more than 200 types",
        "The virus entering through breaks in the skin: cuts, scrapes, even tiny tears from shaving",
        "Touching someone's wart, or surfaces like towels, locker room floors and pool decks",
        "Picking or scratching warts, or biting nails, which spreads them",
        "Walking barefoot in shared areas",
        "Higher risk with a weakened immune system, eczema, pregnancy, and in children and teens"
    ),
    "Scabies" to listOf(
        "The human itch mite (Sarcoptes scabiei var. hominis) burrowing into the skin",
        "Close, prolonged skin-to-skin contact, such as within a household or with sexual partners",
        "Infested bedding, clothing, towels or upholstered furniture",
        "Crowded places such as care homes, dorms and camps",
        "Spreading before any symptoms appear (2 to 6 weeks)",
        "The itch is an allergic reaction to the mites and their droppings"
    )
)

val causeSources: Map<String, List<SourceLink>> = mapOf(
    "Acne Vulgaris" to listOf(
        SourceLink("American Academy of Dermatology: Acne causes", "https://www.aad.org/public/diseases/acne/causes/acne-causes"),
        SourceLink("MedlinePlus: Acne", "https://medlineplus.gov/ency/article/000873.htm")
    ),
    "Eczema" to listOf(
        SourceLink("NIAMS (NIH): Atopic dermatitis", "https://www.niams.nih.gov/health-topics/atopic-dermatitis"),
        SourceLink("NHS: Atopic eczema", "https://www.nhs.uk/conditions/atopic-eczema/")
    ),
    "Melasma" to listOf(
        SourceLink("American Academy of Dermatology: Melasma causes", "https://www.aad.org/public/diseases/a-z/melasma-causes"),
        SourceLink("DermNet: Melasma", "https://dermnetnz.org/topics/melasma")
    ),
    "Tinea" to listOf(
        SourceLink("American Academy of Dermatology: Ringworm causes", "https://www.aad.org/public/diseases/a-z/ringworm-causes"),
        SourceLink("MedlinePlus: Tinea infections", "https://medlineplus.gov/tineainfections.html")
    ),
    "Warts" to listOf(
        SourceLink("American Academy of Dermatology: Warts causes", "https://www.aad.org/public/diseases/a-z/warts-causes"),
        SourceLink("DermNet: Viral warts", "https://dermnetnz.org/topics/viral-warts")
    ),
    "Scabies" to listOf(
        SourceLink("World Health Organization: Scabies", "https://www.who.int/news-room/fact-sheets/detail/scabies"),
        SourceLink("American Academy of Dermatology: Scabies causes", "https://www.aad.org/public/diseases/a-z/scabies-causes")
    )
)

/** References behind each condition's Family Tree entries (see FamilyTree.kt). */
val familyTreeSources: Map<String, List<SourceLink>> = mapOf(
    "Acne Vulgaris" to listOf(
        SourceLink("DermNet: Acne vulgaris", "https://dermnetnz.org/topics/acne-vulgaris"),
        SourceLink("DermNet: Comedonal acne", "https://dermnetnz.org/topics/comedonal-acne")
    ),
    "Eczema" to listOf(
        SourceLink("National Eczema Association: Types of eczema", "https://nationaleczema.org/eczema/types-of-eczema/"),
        SourceLink("American Academy of Dermatology: Eczema types", "https://www.aad.org/public/diseases/eczema/types"),
        SourceLink("DermNet: Seborrheic dermatitis", "https://dermnetnz.org/topics/seborrhoeic-dermatitis"),
        SourceLink("DermNet: Discoid (nummular) eczema", "https://dermnetnz.org/topics/discoid-eczema")
    ),
    "Melasma" to listOf(
        SourceLink("DermNet: Melasma", "https://dermnetnz.org/topics/melasma"),
        SourceLink("DermNet: Postinflammatory hyperpigmentation", "https://dermnetnz.org/topics/postinflammatory-hyperpigmentation"),
        SourceLink("DermNet: Solar lentigo", "https://dermnetnz.org/topics/solar-lentigo"),
        SourceLink("DermNet: Café-au-lait macule", "https://dermnetnz.org/topics/cafe-au-lait-macule")
    ),
    "Tinea" to listOf(
        SourceLink("MedlinePlus: Tinea infections", "https://medlineplus.gov/tineainfections.html"),
        SourceLink("DermNet: Tinea corporis", "https://dermnetnz.org/topics/tinea-corporis"),
        SourceLink("DermNet: Tinea pedis", "https://dermnetnz.org/topics/tinea-pedis"),
        SourceLink("DermNet: Tinea cruris", "https://dermnetnz.org/topics/tinea-cruris"),
        SourceLink("DermNet: Tinea capitis", "https://dermnetnz.org/topics/tinea-capitis"),
        SourceLink("DermNet: Fungal nail infections", "https://dermnetnz.org/topics/fungal-nail-infections")
    ),
    "Warts" to listOf(
        SourceLink("DermNet: Viral warts", "https://dermnetnz.org/topics/viral-warts")
    ),
    "Scabies" to listOf(
        SourceLink("DermNet: Crusted scabies", "https://dermnetnz.org/topics/crusted-scabies"),
        SourceLink("World Health Organization: Scabies", "https://www.who.int/news-room/fact-sheets/detail/scabies")
    )
)

/** "Sources" heading plus one tappable line per reference, each opening in the browser. */
@Composable
fun SourcesList(sources: List<SourceLink>, modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier) {
        Text("Sources", fontSize = settings.textSm.sp, fontWeight = FontWeight.SemiBold, color = settings.textSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Open source") { uriHandler.openUri(source.url) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = DermaGreen, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(source.label, fontSize = settings.textSm.sp, color = DermaGreen, fontWeight = FontWeight.Medium)
            }
        }
    }
}

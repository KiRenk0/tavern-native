package app.tavern.nativeclient

import org.json.JSONObject

// SillyTavern 1.19.1, default/content/presets/novel and src/endpoints/novelai.js.
internal object NovelAiDefaults {
    val presets by lazy { mapOf(
        NovelAiModel.CLIO to NovelAiPreset.parse(JSONObject("""{"order":[1,5,0,2,3,4],"temperature":1.5,"max_length":150,"min_length":1,"top_k":10,"top_p":0.75,"top_a":0.08,"typical_p":0.975,"tail_free_sampling":0.967,"repetition_penalty":2.25,"repetition_penalty_range":8192,"repetition_penalty_slope":0.09,"repetition_penalty_frequency":0,"repetition_penalty_presence":0.005,"use_cache":false,"return_full_text":false,"prefix":"vanilla","phrase_rep_pen":"very_light","max_context":7800,"min_p":0,"math1_temp":1,"math1_quad":0,"math1_quad_entropy_scale":0}"""), "Talker-Chat-Clio"),
        NovelAiModel.KAYRA to NovelAiPreset.parse(JSONObject("""{"order":[2,3,0,4,1],"temperature":1.35,"max_length":150,"min_length":1,"top_k":15,"top_p":0.85,"top_a":0.1,"tail_free_sampling":0.915,"repetition_penalty":2.8,"repetition_penalty_range":2048,"repetition_penalty_slope":0.02,"repetition_penalty_frequency":0.02,"repetition_penalty_presence":0,"use_cache":false,"return_full_text":false,"prefix":"vanilla","phrase_rep_pen":"aggressive","max_context":7800,"min_p":0,"math1_temp":1,"math1_quad":0,"math1_quad_entropy_scale":0}"""), "Carefree-Kayra"),
        NovelAiModel.ERATO to NovelAiPreset.parse(JSONObject("""{"max_context":8000,"temperature":1.37,"max_length":150,"min_length":1,"top_k":0,"top_p":1,"top_a":0.1,"typical_p":0.875,"tail_free_sampling":0.87,"repetition_penalty":3.25,"repetition_penalty_range":6000,"repetition_penalty_slope":3.25,"repetition_penalty_frequency":0,"repetition_penalty_presence":0,"phrase_rep_pen":"off","mirostat_lr":0.2,"mirostat_tau":4,"math1_temp":0.9,"math1_quad":0.07,"math1_quad_entropy_scale":-0.05,"min_p":0.035,"order":[0,5,9,10,8,4]}"""), "Erato-Dragonfruit"),
    ) }

    private val nerdstash by lazy { JSONObject("""
        {
          "bad_words_ids": [[3],[49356],[1431],[31715],[34387],[20765],[30702],[10691],[49333],[1266],[19438],[43145],[26523],[41471],[2936],[85,85],[49332],[7286],[1115],[24]],
          "repetition_penalty_whitelist": [49256,49264,49231,49230,49287,85,49255,49399,49262,336,333,432,363,468,492,745,401,426,623,794,1096,2919,2072,7379,1259,2110,620,526,487,16562,603,805,761,2681,942,8917,653,3513,506,5301,562,5010,614,10942,539,2976,462,5189,567,2032,123,124,125,126,127,128,129,130,131,132,588,803,1040,49209,4,5,6,7,8,9,10,11,12],
          "logit_bias_exp": [{"sequence":[23],"bias":-0.08,"ensure_sequence_finish":false,"generate_once":false},{"sequence":[21],"bias":-0.08,"ensure_sequence_finish":false,"generate_once":false}]
        }
    """) }

    private val erato by lazy { JSONObject("""
        {
          "bad_words_ids": [[16067],[933,11144],[25106,11144],[58,106901,16073,33710,25,109933],[933,58,11144],[128030],[58,30591,33503,17663,100204,25,11144]],
          "repetition_penalty_whitelist": [6,1,11,13,25,198,12,9,8,279,264,459,323,477,539,912,374,574,1051,1550,1587,4536,5828,15058,3287,3250,1461,1077,813,11074,872,1202,1436,7846,1288,13434,1053,8434,617,9167,1047,19117,706,12775,649,4250,527,7784,690,2834,15,16,17,18,19,20,21,22,23,24,1210,1359,608,220,596,956,3077,44886,4265,3358,2351,2846,311,389,315,304,520,505,430],
          "logit_bias_exp": [{"sequence":[12488],"bias":-0.08,"ensure_sequence_finish":false,"generate_once":false},{"sequence":[128041],"bias":-0.08,"ensure_sequence_finish":false,"generate_once":false}]
        }
    """) }

    fun tokenRules(model: NovelAiModel): JSONObject = if (model == NovelAiModel.ERATO) erato else nerdstash
}

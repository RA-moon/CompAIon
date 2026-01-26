## Model ZIP for UI Download

The **Download Model** button expects a `.zip` file with a model folder that contains
`mlc-chat-config.json`, weights, and **`lib<model_lib>.so`**.

### Required structure
```
model.zip
└─ <model_folder>/
   ├─ mlc-chat-config.json
   ├─ model_lib.txt
   ├─ lib<model_lib>.so
   ├─ params_shard_*.bin
   ├─ tokenizer.json
   ├─ tokenizer_config.json
   └─ vocab.json
```

### Build a ZIP from local cache
Use the helper script:
```
scripts/build_model_zip.sh \
  --model-dir ~/.cache/mlc_llm/model_weights/hf/mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC \
  --model-lib qwen2_q4f16_1_95967267c464e10967be161a66e856d4 \
  --model-so /path/to/libqwen2_q4f16_1_95967267c464e10967be161a66e856d4.so \
  --out dist/model-zips/Qwen2.5-0.5B-Instruct-q4f16_1-MLC.zip
```

### Example ZIP (template)
```
dist/model-zips/Qwen2.5-0.5B-Instruct-q4f16_1-MLC.zip
```
This example was created **without** a `lib<model_lib>.so` and will **not load** unless you add the `.so`.


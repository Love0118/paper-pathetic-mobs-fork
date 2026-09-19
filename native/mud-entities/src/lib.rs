//! JNI batch operating on real NMS Entity objects, through JVM-supported method calls.
//! Never retain a local reference, JNIEnv or array pointer past a native call.
use jni::{
    JNIEnv,
    objects::{GlobalRef, JClass, JDoubleArray, JMethodID, JObjectArray},
    signature::{Primitive, ReturnType},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jvalue},
};
use std::sync::OnceLock;

struct Methods {
    _class: GlobalRef,
    position: JMethodID,
    yaw: JMethodID,
    head_yaw: JMethodID,
    pitch: JMethodID,
}
static METHODS: OnceLock<Methods> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_papermc_paper_optimization_mud_MudNativeEntities_init0(
    mut env: JNIEnv,
    _class: JClass,
    entity_class: JClass,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<()> {
            let methods = Methods {
                _class: env.new_global_ref(&entity_class)?,
                position: env.get_method_id(&entity_class, "setPos", "(DDD)V")?,
                yaw: env.get_method_id(&entity_class, "setYRot", "(F)V")?,
                head_yaw: env.get_method_id(&entity_class, "setYHeadRot", "(F)V")?,
                pitch: env.get_method_id(&entity_class, "setXRot", "(F)V")?,
            };
            let _ = METHODS.set(methods);
            Ok(())
        },
    ));
    match result {
        Ok(Ok(())) => 1,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_papermc_paper_optimization_mud_MudNativeEntities_move0(
    mut env: JNIEnv,
    _class: JClass,
    entities: JObjectArray,
    coordinates: JDoubleArray,
    count: jint,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> jni::errors::Result<bool> {
            let Some(methods) = METHODS.get() else {
                return Ok(false);
            };
            if !(0..=4096).contains(&count)
                || env.get_array_length(&entities)? < count
                || env.get_array_length(&coordinates)? < count * 5
            {
                return Ok(false);
            }
            // Copy first: no pinned primitive array or GC lock while invoking Java methods.
            let mut values = vec![0.0; count as usize * 5];
            env.get_double_array_region(&coordinates, 0, &mut values)?;
            if values.iter().any(|v| !v.is_finite()) {
                return Ok(false);
            }
            for i in 0..count {
                let entity = env.get_object_array_element(&entities, i)?;
                if entity.is_null() {
                    return Ok(false);
                }
                // Java prevalidates every entry before invocation; methods belong to Entity and
                // every element is an Entity[] member. The global class reference keeps IDs valid.
                let row = &values[i as usize * 5..];
                unsafe {
                    env.call_method_unchecked(
                        &entity,
                        methods.position,
                        ReturnType::Primitive(Primitive::Void),
                        &[
                            jvalue { d: row[0] },
                            jvalue { d: row[1] },
                            jvalue { d: row[2] },
                        ],
                    )?;
                    env.call_method_unchecked(
                        &entity,
                        methods.yaw,
                        ReturnType::Primitive(Primitive::Void),
                        &[jvalue { f: row[3] as f32 }],
                    )?;
                    env.call_method_unchecked(
                        &entity,
                        methods.head_yaw,
                        ReturnType::Primitive(Primitive::Void),
                        &[jvalue { f: row[3] as f32 }],
                    )?;
                    env.call_method_unchecked(
                        &entity,
                        methods.pitch,
                        ReturnType::Primitive(Primitive::Void),
                        &[jvalue { f: row[4] as f32 }],
                    )?;
                }
                env.delete_local_ref(entity)?;
            }
            Ok(true)
        },
    ));
    match result {
        Ok(Ok(true)) => JNI_TRUE,
        Ok(Ok(false)) => JNI_FALSE,
        _ => {
            // Never retry an interrupted batch: earlier entities might already have moved.
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "MUD JNI entity batch failed; no retry performed",
                );
            }
            JNI_FALSE
        }
    }
}

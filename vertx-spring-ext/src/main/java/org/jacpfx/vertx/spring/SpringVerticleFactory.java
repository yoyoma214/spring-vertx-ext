
/*
 * Copyright (C) 2014
 *
 * [SpringVerticleFactory.java]
 * vertx-spring-mod (https://github.com/amoAHCP/spring-vertx-mod)
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.jacpfx.vertx.spring;


import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.impl.verticle.CompilingClassLoader;
import io.vertx.core.logging.Logger;
import io.vertx.core.spi.VerticleFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * A Vertx Spring factory that creates a spring verticle and loads for each verticle instance a spring context
 *
 */
public class SpringVerticleFactory implements VerticleFactory {
    private Vertx vertx;

    public static final String PREFIX = "java-spring";

    @Override
    public String prefix() {
        return PREFIX;
    }

    
    @Override
    public void init(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public synchronized Verticle createVerticle(String verticleName, ClassLoader classLoader) throws Exception {
        verticleName = VerticleFactory.removePrefix(verticleName);
        System.out.println("LOAD: " + verticleName + " in THREAD: " + Thread.currentThread() + "  in Factory:" + this);

        String className = verticleName;
        Class<?> clazz;

        if (className.endsWith(".java")) {
            CompilingClassLoader compilingLoader = new CompilingClassLoader(classLoader, className);
            className = compilingLoader.resolveMainClassName();
            clazz = compilingLoader.loadClass(className);
        } else {
            clazz = classLoader.loadClass(className);
        }
        Verticle verticle = createVerticle(clazz, classLoader);
        return verticle;
    }

    public synchronized Verticle createVerticle(Class<?> clazz, ClassLoader classLoader) throws Exception {

        if (clazz.isAnnotationPresent(SpringVerticle.class)) {
            return createSpringVerticle(clazz, classLoader);
        } else if (Verticle.class.isAssignableFrom(clazz)) {
            // init a non spring verticle, but this should not happen
            final Verticle verticle = Verticle.class.cast(clazz.newInstance());
            verticle.init(vertx, vertx.getOrCreateContext());
            return verticle;
        }

        return null;
    }

    private Verticle createSpringVerticle(final Class<?> currentVerticleClass, ClassLoader classLoader) {
        final SpringVerticle annotation = currentVerticleClass.getAnnotation(SpringVerticle.class);
        final Class<?> springConfigClass = annotation.springConfig();
        
        // Create the parent context  
        final GenericApplicationContext genericApplicationContext = new GenericApplicationContext();
        genericApplicationContext.setClassLoader(classLoader);
        genericApplicationContext.refresh();
        genericApplicationContext.start();

        // 1. Create a new context for each verticle and use the specified spring configuration class if possible
        AnnotationConfigApplicationContext annotationConfigApplicationContext = new AnnotationConfigApplicationContext();
        annotationConfigApplicationContext.setParent(genericApplicationContext);
        annotationConfigApplicationContext.register(SpringContextConfiguration.class, springConfigClass);

        // 2. Register a bean definition for this verticle
        annotationConfigApplicationContext.registerBeanDefinition(currentVerticleClass.getSimpleName(), new VerticleBeanDefinition(currentVerticleClass));
        
        // 3. Add a bean factory post processor to avoid configuration issues
        annotationConfigApplicationContext.addBeanFactoryPostProcessor(new SpringSingleVerticleConfiguration(currentVerticleClass));
        annotationConfigApplicationContext.refresh();
        annotationConfigApplicationContext.start();
        annotationConfigApplicationContext.registerShutdownHook();
        
        // 5. Return the verticle by fetching the bean from the context
        return (Verticle) annotationConfigApplicationContext.getBeanFactory().getBean(currentVerticleClass.getSimpleName());
    }

    public void reportException(Logger logger, Throwable t) {
        logger.error("Exception in Spring verticle", t);
    }

    public void close() {
    }
}